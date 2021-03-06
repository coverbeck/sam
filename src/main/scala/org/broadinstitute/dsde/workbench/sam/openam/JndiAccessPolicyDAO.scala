package org.broadinstitute.dsde.workbench.sam.openam

import java.util.Date
import javax.naming.directory._
import javax.naming.{NameAlreadyBoundException, NameNotFoundException}

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.directory.DirectorySubjectNameSupport
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.{Attr, ObjectClass}
import org.broadinstitute.dsde.workbench.sam.util.{BaseDirContext, JndiSupport}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Created by dvoet on 6/26/17.
  */
class JndiAccessPolicyDAO(protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends AccessPolicyDAO with DirectorySubjectNameSupport with JndiSupport {
  //
  // RESOURCE TYPES
  //

  override def createResourceType(resourceTypeName: ResourceTypeName): Future[ResourceTypeName] = withContext { ctx =>
    try {
      val resourceContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true) // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", ObjectClass.resourceType).foreach(oc.add)
          myAttrs.put(oc)
          myAttrs.put(Attr.ou, "resources")

          myAttrs
        }
      }

      ctx.bind(resourceTypeDn(resourceTypeName), resourceContext)
      resourceTypeName
    } catch {
      case _: NameAlreadyBoundException => resourceTypeName //resource type has already been created, this is OK
    }
  }

  //
  // RESOURCES
  //

  override def createResource(resource: Resource): Future[Resource] = withContext { ctx =>
    try {
      val resourceContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true) // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", ObjectClass.resource).foreach(oc.add)
          myAttrs.put(oc)
          myAttrs.put(Attr.resourceType, resource.resourceTypeName.value)

          myAttrs
        }
      }

      ctx.bind(resourceDn(resource), resourceContext)
      resource
    } catch {
      case _: NameAlreadyBoundException => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists"))
    }
  }

  // TODO: Method is not tested.  To test properly, we'll probably need a loadResource or getResource method
  override def deleteResource(resource: Resource): Future[Unit] = withContext { ctx =>
    ctx.unbind(resourceDn(resource))
  }

  //
  // POLICIES
  //

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = withContext { ctx =>
    try {
      val policyContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true) // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", ObjectClass.policy).foreach(oc.add)
          myAttrs.put(oc)
          myAttrs.put(Attr.cn, policy.id.accessPolicyName.value)
          myAttrs.put(Attr.email, policy.email.value) //TODO make sure the google group is created

          if (policy.actions.nonEmpty) {
            val actions = new BasicAttribute(Attr.action)
            policy.actions.foreach(action => actions.add(action.value))
            myAttrs.put(actions)
          }

          if (policy.roles.nonEmpty) {
            val roles = new BasicAttribute(Attr.role)
            policy.roles.foreach(role => roles.add(role.value))
            myAttrs.put(roles)
          }

          if(policy.members.nonEmpty) {
            val members = new BasicAttribute(Attr.uniqueMember)

            val memberDns = policy.members.map { s =>
              subjectDn(s)
            }

            memberDns.foreach(members.add)

            myAttrs.put(members)
          }

          myAttrs.put(Attr.resourceType, policy.id.resource.resourceTypeName.value)
          myAttrs.put(Attr.resourceId, policy.id.resource.resourceId.value)
          myAttrs.put(Attr.groupUpdatedTimestamp, formattedDate(new Date()))
          myAttrs
        }
      }

      ctx.bind(policyDn(policy.id), policyContext)
      policy
    } catch {
      case _: NameAlreadyBoundException => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A policy by this name already exists for this resource"))
    }
  }

  override def deletePolicy(policy: AccessPolicy): Future[Unit] = withContext { ctx =>
    ctx.unbind(policyDn(policy.id))
  }

  override def overwritePolicy(newPolicy: AccessPolicy): Future[AccessPolicy] = withContext { ctx =>
    val myAttrs = new BasicAttributes(true)

    val users = newPolicy.members.collect { case userId: WorkbenchUserId => userId }
    val subGroups = newPolicy.members.collect { case groupName: WorkbenchGroupName => groupName }

    addMemberAttributes(users, subGroups, myAttrs) { _.put(new BasicAttribute(Attr.uniqueMember)) } //add attribute with no value when no member present

    val actions = new BasicAttribute(Attr.action)
    newPolicy.actions.foreach(action => actions.add(action.value))
    myAttrs.put(actions)

    val roles = new BasicAttribute(Attr.role)
    newPolicy.roles.foreach(role => roles.add(role.value))
    myAttrs.put(roles)

    myAttrs.put(Attr.groupUpdatedTimestamp, formattedDate(new Date()))

    ctx.modifyAttributes(policyDn(newPolicy.id), DirContext.REPLACE_ATTRIBUTE, myAttrs)
    newPolicy
  }

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): Future[Set[ResourceIdAndPolicyName]] = withContext { ctx =>
    val attributes = ctx.getAttributes(userDn(userId), Array(Attr.memberOf))
    val groupDns = getAttributes[String](attributes, Attr.memberOf).getOrElse(Set.empty).toSet

    val policyDnPattern = dnMatcher(Seq(Attr.policy, Attr.resourceId), resourceTypeDn(resourceTypeName))

    groupDns.collect {
      case policyDnPattern(policyName, resourceId) => ResourceIdAndPolicyName(ResourceId(resourceId), AccessPolicyName(policyName))
    }
  }

  override def listAccessPolicies(resource: Resource): Future[Set[AccessPolicy]] = {
    val searchAttrs = new BasicAttributes(true)  // Case ignore

    listAccessPolicies(resource, searchAttrs)
  }

  override def listAccessPoliciesForUser(resource: Resource, user: WorkbenchUserId): Future[Set[AccessPolicy]] = {
    withContext { ctx =>
      val memberOfAttribute = Option(ctx.getAttributes(subjectDn(user), Array(Attr.memberOf)).get(Attr.memberOf))
      val memberOfDns = memberOfAttribute.map(_.getAll.extractResultsAndClose.asInstanceOf[Seq[String]]).getOrElse(Seq.empty)
      val memberOfSubjects = memberOfDns.map(dnToSubject)
      memberOfSubjects.collect {
        case ripn@ResourceAndPolicyName(`resource`, _) => unmarshalAccessPolicy(ctx.getAttributes(subjectDn(ripn)))
      }.toSet
    }
  }

  override def loadPolicy(resourceAndPolicyName: ResourceAndPolicyName): Future[Option[AccessPolicy]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(policyDn(resourceAndPolicyName))
      Option(unmarshalAccessPolicy(attributes))
    }.recover {
      case e: NameNotFoundException => None
    }.get
  }

  override def listFlattenedPolicyMembers(resourceAndPolicyName: ResourceAndPolicyName): Future[Set[WorkbenchUserId]] = withContext { ctx =>
    ctx.search(peopleOu, new BasicAttributes(Attr.memberOf, policyDn(resourceAndPolicyName), true)).extractResultsAndClose.map { result =>
      unmarshalUser(result.getAttributes).id
    }.toSet
  }

  //
  // SUPPORT
  //

  private def getAttributes[T](attributes: Attributes, key: String): Option[TraversableOnce[T]] = {
    Option(attributes.get(key)).map(_.getAll.extractResultsAndClose.map(_.asInstanceOf[T]))
  }

  private def unmarshalUser(attributes: Attributes): WorkbenchUser = {
    val uid = getAttribute[String](attributes, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))

    WorkbenchUser(WorkbenchUserId(uid), WorkbenchEmail(email))
  }

  private def getAttribute[T](attributes: Attributes, key: String): Option[T] = {
    Option(attributes.get(key)).map(_.get.asInstanceOf[T])
  }

  private def unmarshalAccessPolicy(attributes: Attributes): AccessPolicy = {
    val policyName = attributes.get(Attr.policy).get().toString
    val resourceTypeName = ResourceTypeName(attributes.get(Attr.resourceType).get().toString)
    val resourceId = ResourceId(attributes.get(Attr.resourceId).get().toString)
    val resource = Resource(resourceTypeName, resourceId)
    val members = getAttributes[String](attributes, Attr.uniqueMember).getOrElse(Set.empty).toSet.map(dnToSubject)
    val roles = getAttributes[String](attributes, Attr.role).getOrElse(Set.empty).toSet.map(r => ResourceRoleName(r))
    val actions = getAttributes[String](attributes, Attr.action).getOrElse(Set.empty).toSet.map(a => ResourceAction(a))

    val email = WorkbenchEmail(attributes.get(Attr.email).get().toString)

    AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName(policyName)), members, email, roles, actions)
  }

  /**
    * @param emptyValueFn a function called when no members are present
    ** /
    */
  private def addMemberAttributes(users: Set[WorkbenchUserId], subGroups: Set[WorkbenchGroupName], myAttrs: BasicAttributes)(emptyValueFn: BasicAttributes => Unit): Any = {
    val memberDns = users.map(user => userDn(user)) ++ subGroups.map(subGroup => groupDn(subGroup))
    if (memberDns.nonEmpty) {
      val members = new BasicAttribute(Attr.uniqueMember)
      memberDns.foreach(subject => members.add(subject))
      myAttrs.put(members)
    } else {
      emptyValueFn(myAttrs)
    }
  }

  private def listAccessPolicies(resource: Resource, searchAttrs: BasicAttributes): Future[Set[AccessPolicy]] = withContext { ctx =>
    val policies = try {
      ctx.search(resourceDn(resource), searchAttrs).extractResultsAndClose.map { searchResult =>
        unmarshalAccessPolicy(searchResult.getAttributes)
      }
    } catch {
      case _: NameNotFoundException => Set.empty
    }

    policies.toSet
  }

  private def withContext[T](op: InitialDirContext => T): Future[T] = withContext(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(op)
}
