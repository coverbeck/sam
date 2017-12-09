package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.ExtensionRoutes

import scala.concurrent.{ExecutionContext, Future}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem

trait CloudExtensions {
  val allUsersGroupName = WorkbenchGroupName("All_Users")

  // this is temporary until we get the admin group rolled into a sam group
  def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean]

  def onBoot()(implicit system: ActorSystem): Unit

  def onGroupUpdate(groupIdentities: Seq[WorkbenchGroupIdentity]): Future[Unit]

  def onUserCreate(user: WorkbenchUser): Future[Unit]

  def getUserStatus(user: WorkbenchUser): Future[Boolean]

  def onUserEnable(user: WorkbenchUser): Future[Unit]

  def onUserDisable(user: WorkbenchUser): Future[Unit]

  def onUserDelete(userId: WorkbenchUserId): Future[Unit]

  @deprecated
  def deleteUserPetServiceAccount(userId: WorkbenchUserId): Future[Boolean]

  def deleteUserPetServiceAccount(userId: WorkbenchUserId, project: GoogleProject): Future[Boolean]

  def checkStatus: Map[Subsystem, Future[SubsystemStatus]]

  def allSubSystems: Set[Subsystem]

  def emailDomain: String

  def getOrCreateAllUsersGroup(directoryDAO: DirectoryDAO)(implicit executionContext: ExecutionContext): Future[WorkbenchGroup]
}

trait NoExtensions extends CloudExtensions {
  override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = Future.successful(true)

  override def onBoot()(implicit system: ActorSystem): Unit = {}

  override def onGroupUpdate(groupIdentities: Seq[WorkbenchGroupIdentity]): Future[Unit] = Future.successful(())

  override def onUserCreate(user: WorkbenchUser): Future[Unit] = Future.successful(())

  override def getUserStatus(user: WorkbenchUser): Future[Boolean] = Future.successful(true)

  override def onUserEnable(user: WorkbenchUser): Future[Unit] = Future.successful(())

  override def onUserDisable(user: WorkbenchUser): Future[Unit] = Future.successful(())

  override def onUserDelete(userId: WorkbenchUserId): Future[Unit] = Future.successful(())

  @deprecated
  override def deleteUserPetServiceAccount(userId: WorkbenchUserId): Future[Boolean] = Future.successful(true)

  override def deleteUserPetServiceAccount(userId: WorkbenchUserId, project: GoogleProject): Future[Boolean] = Future.successful(true)

  override def checkStatus: Map[Subsystem, Future[SubsystemStatus]] = Map.empty

  override def allSubSystems: Set[Subsystem] = Set.empty

  override val emailDomain = "example.com"

  override def getOrCreateAllUsersGroup(directoryDAO: DirectoryDAO)(implicit executionContext: ExecutionContext): Future[WorkbenchGroup] = {
    val allUsersGroup = BasicWorkbenchGroup(allUsersGroupName, Set.empty, WorkbenchEmail(s"GROUP_${allUsersGroupName.value}@$emailDomain"))
    for {
      createdGroup <- directoryDAO.createGroup(allUsersGroup) recover {
        case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) => allUsersGroup
      }
    } yield createdGroup
  }
}

object NoExtensions extends NoExtensions

trait NoExtensionRoutes extends ExtensionRoutes {
  def extensionRoutes: server.Route = reject
  val cloudExtensions = NoExtensions
}