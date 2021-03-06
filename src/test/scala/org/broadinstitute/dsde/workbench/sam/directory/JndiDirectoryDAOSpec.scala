package org.broadinstitute.dsde.workbench.sam.directory

import java.util.UUID
import javax.naming.NameNotFoundException

import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.{DirectoryConfig, SchemaLockConfig}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 5/30/17.
  */
class JndiDirectoryDAOSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val schemaLockConfig = ConfigFactory.load().as[SchemaLockConfig]("schemaLock")
  val dao = new JndiDirectoryDAO(directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
  }


  "JndiGroupDirectoryDAO" should "create, read, delete groups" in {
    val groupName = WorkbenchGroupName(UUID.randomUUID().toString)
    val group = BasicWorkbenchGroup(groupName, Set.empty, WorkbenchEmail("john@doe.org"))

    assertResult(None) {
      runAndWait(dao.loadGroup(group.id))
    }

    assertResult(group) {
      runAndWait(dao.createGroup(group))
    }

    assertResult(Some(group)) {
      runAndWait(dao.loadGroup(group.id))
    }

    runAndWait(dao.deleteGroup(group.id))

    assertResult(None) {
      runAndWait(dao.loadGroup(group.id))
    }
  }

  it should "create, read, delete users" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchEmail("foo@bar.com"))

    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }

    assertResult(user) {
      runAndWait(dao.createUser(user))
    }

    assertResult(Some(user)) {
      runAndWait(dao.loadUser(user.id))
    }

    runAndWait(dao.deleteUser(user.id))

    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }
  }

  it should "add and read proxy group email" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchEmail("foo@bar.com"))

    assertResult(user) {
      runAndWait(dao.createUser(user))
    }

    assertResult(None) {
      runAndWait(dao.readProxyGroup(userId))
    }

    runAndWait(dao.addProxyGroup(userId, WorkbenchEmail("foo_1234@test.firecloud.org")))

    assertResult(Some(WorkbenchEmail("foo_1234@test.firecloud.org"))) {
      runAndWait(dao.readProxyGroup(userId))
    }
  }

  it should "create, read, delete pet service accounts" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchEmail("foo@bar.com"))
    val serviceAccountUniqueId = ServiceAccountSubjectId(UUID.randomUUID().toString)
    val serviceAccount = ServiceAccount(serviceAccountUniqueId, WorkbenchEmail("foo@bar.com"), ServiceAccountDisplayName(""))
    val project = GoogleProject("testproject")
    val petServiceAccount = PetServiceAccount(PetServiceAccountId(userId, project), serviceAccount)

    assertResult(user) {
      runAndWait(dao.createUser(user))
    }

    assertResult(None) {
      runAndWait(dao.loadPetServiceAccount(petServiceAccount.id))
    }

    assertResult(Seq()) {
      runAndWait(dao.getAllPetServiceAccountsForUser(userId))
    }

    assertResult(petServiceAccount) {
      runAndWait(dao.createPetServiceAccount(petServiceAccount))
    }

    assertResult(Some(petServiceAccount)) {
      runAndWait(dao.loadPetServiceAccount(petServiceAccount.id))
    }

    assertResult(Seq(petServiceAccount)) {
      runAndWait(dao.getAllPetServiceAccountsForUser(userId))
    }

    val updatedPetSA = petServiceAccount.copy(serviceAccount = ServiceAccount(ServiceAccountSubjectId(UUID.randomUUID().toString), WorkbenchEmail("foo@bar.com"), ServiceAccountDisplayName("qqq")))
    assertResult(updatedPetSA) {
      runAndWait(dao.updatePetServiceAccount(updatedPetSA))
    }

    assertResult(Some(updatedPetSA)) {
      runAndWait(dao.loadPetServiceAccount(petServiceAccount.id))
    }

    runAndWait(dao.deletePetServiceAccount(petServiceAccount.id))

    assertResult(None) {
      runAndWait(dao.loadPetServiceAccount(petServiceAccount.id))
    }

    assertResult(Seq()) {
      runAndWait(dao.getAllPetServiceAccountsForUser(userId))
    }
  }

  it should "list groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(groupName1), WorkbenchEmail("g2@example.com"))

    runAndWait(dao.createUser(user))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))

    try {
      assertResult(Set(groupName1, groupName2)) {
        runAndWait(dao.listUsersGroups(userId))
      }
    } finally {
      runAndWait(dao.deleteUser(userId))
      runAndWait(dao.deleteGroup(groupName2))
      runAndWait(dao.deleteGroup(groupName1))
    }
  }

  it should "list flattened group users" in {
    val userId1 = WorkbenchUserId(UUID.randomUUID().toString)
    val user1 = WorkbenchUser(userId1, WorkbenchEmail("foo@bar.com"))
    val userId2 = WorkbenchUserId(UUID.randomUUID().toString)
    val user2 = WorkbenchUser(userId2, WorkbenchEmail("foo@bar.com"))
    val userId3 = WorkbenchUserId(UUID.randomUUID().toString)
    val user3 = WorkbenchUser(userId3, WorkbenchEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId1), WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(userId2, groupName1), WorkbenchEmail("g2@example.com"))

    val groupName3 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group3 = BasicWorkbenchGroup(groupName3, Set(userId3, groupName2), WorkbenchEmail("g3@example.com"))

    runAndWait(dao.createUser(user1))
    runAndWait(dao.createUser(user2))
    runAndWait(dao.createUser(user3))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))
    runAndWait(dao.createGroup(group3))

    try {
      assertResult(Set(userId1, userId2, userId3)) {
        runAndWait(dao.listFlattenedGroupUsers(groupName3))
      }
    } finally {
      runAndWait(dao.deleteUser(userId1))
      runAndWait(dao.deleteUser(userId2))
      runAndWait(dao.deleteUser(userId3))
      runAndWait(dao.deleteGroup(groupName3))
      runAndWait(dao.deleteGroup(groupName2))
      runAndWait(dao.deleteGroup(groupName1))
    }
  }

  it should "list group ancestors" in {
    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(), WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(groupName1), WorkbenchEmail("g2@example.com"))

    val groupName3 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group3 = BasicWorkbenchGroup(groupName3, Set(groupName2), WorkbenchEmail("g3@example.com"))

    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))
    runAndWait(dao.createGroup(group3))

    try {
      assertResult(Set(groupName2, groupName3)) {
        runAndWait(dao.listAncestorGroups(groupName1))
      }
    } finally {
      runAndWait(dao.deleteGroup(groupName3))
      runAndWait(dao.deleteGroup(groupName2))
      runAndWait(dao.deleteGroup(groupName1))
    }
  }

  it should "handle circular groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(groupName1), WorkbenchEmail("g2@example.com"))

    val groupName3 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group3 = BasicWorkbenchGroup(groupName3, Set(groupName2), WorkbenchEmail("g3@example.com"))

    runAndWait(dao.createUser(user))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))
    runAndWait(dao.createGroup(group3))

    runAndWait(dao.addGroupMember(groupName1, groupName3))

    try {
      assertResult(Set(userId)) {
        runAndWait(dao.listFlattenedGroupUsers(groupName3))
      }

      assertResult(Set(groupName1, groupName2, groupName3)) {
        runAndWait(dao.listUsersGroups(userId))
      }

      assertResult(Set(groupName1, groupName2, groupName3)) {
        runAndWait(dao.listAncestorGroups(groupName3))
      }
    } finally {
      runAndWait(dao.deleteUser(userId))
      runAndWait(dao.removeGroupMember(groupName1, groupName3))
      runAndWait(dao.deleteGroup(groupName3))
      runAndWait(dao.deleteGroup(groupName2))
      runAndWait(dao.deleteGroup(groupName1))
    }
  }

  it should "add/remove groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set.empty, WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set.empty, WorkbenchEmail("g2@example.com"))

    runAndWait(dao.createUser(user))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))

    try {

      runAndWait(dao.addGroupMember(groupName1, userId))

      assertResult(Some(group1.copy(members = Set(userId)))) {
        runAndWait(dao.loadGroup(groupName1))
      }

      runAndWait(dao.addGroupMember(groupName1, groupName2))

      assertResult(Some(group1.copy(members = Set(userId, groupName2)))) {
        runAndWait(dao.loadGroup(groupName1))
      }

      runAndWait(dao.removeGroupMember(groupName1, userId))

      assertResult(Some(group1.copy(members = Set(groupName2)))) {
        runAndWait(dao.loadGroup(groupName1))
      }

      runAndWait(dao.removeGroupMember(groupName1, groupName2))

      assertResult(Some(group1)) {
        runAndWait(dao.loadGroup(groupName1))
      }

    } finally {
      runAndWait(dao.deleteUser(userId))
      runAndWait(dao.deleteGroup(groupName1))
      runAndWait(dao.deleteGroup(groupName2))
    }
  }

  it should "handle different kinds of groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set.empty, WorkbenchEmail("g2@example.com"))

    runAndWait(dao.createUser(user))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))

    val policyDAO = new JndiAccessPolicyDAO(directoryConfig)

    val typeName1 = ResourceTypeName(UUID.randomUUID().toString)

    val policy1 = AccessPolicy(ResourceAndPolicyName(Resource(typeName1, ResourceId("resource")), AccessPolicyName("role1-a")), Set(userId), WorkbenchEmail("p1@example.com"), Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")))

    runAndWait(policyDAO.createResourceType(typeName1))
    runAndWait(policyDAO.createResource(policy1.id.resource))
    runAndWait(policyDAO.createPolicy(policy1))

    assert(runAndWait(dao.isGroupMember(group1.id, userId)))
    assert(!runAndWait(dao.isGroupMember(group2.id, userId)))
    assert(runAndWait(dao.isGroupMember(policy1.id, userId)))
  }

  it should "get pet for user" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchEmail("foo@bar.com"))

    runAndWait(dao.createUser(user))

    val serviceAccount = ServiceAccount(ServiceAccountSubjectId("09834572039847519384"), WorkbenchEmail("foo@sa.com"), ServiceAccountDisplayName("blarg"))
    val pet = PetServiceAccount(PetServiceAccountId(userId, GoogleProject("foo")), serviceAccount)

    runAndWait(dao.loadPetServiceAccount(pet.id)) shouldBe None
    runAndWait(dao.createPetServiceAccount(pet)) shouldBe pet
    runAndWait(dao.loadPetServiceAccount(pet.id)) shouldBe Some(pet)
    runAndWait(dao.getUserFromPetServiceAccount(serviceAccount.subjectId)) shouldBe Some(user)

    // uid that does not exist
    runAndWait(dao.getUserFromPetServiceAccount(ServiceAccountSubjectId("asldkasfa"))) shouldBe None

    // uid that does exist but is not a pet
    runAndWait(dao.getUserFromPetServiceAccount(ServiceAccountSubjectId(user.id.value))) shouldBe None
  }

  "JndiDirectoryDAO safeDelete" should "prevent deleting groups that are sub-groups of other groups" in {
    val childGroupName = WorkbenchGroupName(UUID.randomUUID().toString)
    val childGroup = BasicWorkbenchGroup(childGroupName, Set.empty, WorkbenchEmail("donnie@hollywood-lanes.com"))

    val parentGroupName = WorkbenchGroupName(UUID.randomUUID().toString)
    val parentGroup = BasicWorkbenchGroup(parentGroupName, Set(childGroupName), WorkbenchEmail("walter@hollywood-lanes.com"))

    assertResult(None) {
      runAndWait(dao.loadGroup(childGroupName))
    }

    assertResult(None) {
      runAndWait(dao.loadGroup(parentGroupName))
    }

    assertResult(childGroup) {
      runAndWait(dao.createGroup(childGroup))
    }

    assertResult(parentGroup) {
      runAndWait(dao.createGroup(parentGroup))
    }

    assertResult(Some(childGroup)) {
      runAndWait(dao.loadGroup(childGroupName))
    }

    assertResult(Some(parentGroup)) {
      runAndWait(dao.loadGroup(parentGroupName))
    }

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(dao.deleteGroup(childGroupName))
    }

    assertResult(Some(childGroup)) {
      runAndWait(dao.loadGroup(childGroupName))
    }

    assertResult(Some(parentGroup)) {
      runAndWait(dao.loadGroup(parentGroupName))
    }
  }

  "JndiDirectoryDao loadSubjectEmail" should "fail if the user has not been created" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchEmail("foo@bar.com"))

    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }

    runAndWait(dao.loadSubjectEmail(userId)) shouldEqual None
  }

  it should "succeed if the user has been created" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, WorkbenchEmail("foo@bar.com"))

    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }

    assertResult(user) {
      runAndWait(dao.createUser(user))
    }

    assertResult(Some(user)) {
      runAndWait(dao.loadUser(user.id))
    }

    assertResult(Some(user.email)) {
      runAndWait(dao.loadSubjectEmail(userId))
    }
  }
}


