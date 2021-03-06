package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchSubject}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, UserService}
import spray.json.JsBoolean

import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 5/22/17.
  */
trait ResourceRoutes extends UserInfoDirectives with SecurityDirectives with SamModelDirectives {
  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService

  def withResourceType(name: ResourceTypeName): Directive1[ResourceType] = {
    onSuccess(resourceService.getResourceType(name)).map {
      case Some(resourceType) => resourceType
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"resource type ${name.value} not found"))
    }
  }

  def resourceRoutes: server.Route =
    (pathPrefix("config" / "v1" / "resourceTypes") | pathPrefix("resourceTypes")) {
      requireUserInfo { userInfo =>
        pathEndOrSingleSlash {
          get {
            complete {
              resourceService.getResourceTypes().map(typeMap => StatusCodes.OK -> typeMap.values.toSet)
            }
          }
        }
      }
    } ~
    (pathPrefix("resources" / "v1") | pathPrefix("resource")) {
      requireUserInfo { userInfo =>
        pathPrefix(Segment) { resourceTypeName =>
          withResourceType(ResourceTypeName(resourceTypeName)) { resourceType =>
            pathEndOrSingleSlash {
              get {
                complete(resourceService.listUserAccessPolicies(resourceType, userInfo))
              } ~
              post {
                entity(as[CreateResourceRequest]) { createResourceRequest =>
                  if (resourceType.reuseIds) {
                    throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "this api may not be used for resource types that allow id reuse"))
                  }
                  complete(resourceService.createResource(resourceType, createResourceRequest.resourceId, createResourceRequest.policies, createResourceRequest.authDomain, userInfo).map(_ => StatusCodes.NoContent))
                }
              }
            } ~
            pathPrefix(Segment) { resourceId =>

              val resource = Resource(resourceType.name, ResourceId(resourceId))

              pathEndOrSingleSlash {
                delete {
                  requireAction(resource, SamResourceActions.delete, userInfo) {
                    complete(resourceService.deleteResource(Resource(resourceType.name, ResourceId(resourceId))).map(_ => StatusCodes.NoContent))
                  }
                } ~
                post {
                  complete(resourceService.createResource(resourceType, ResourceId(resourceId), userInfo).map(_ => StatusCodes.NoContent))
                }
              } ~
              pathPrefix("action") {
                pathPrefix(Segment) { action =>
                  pathEndOrSingleSlash {
                    get {
                      complete(resourceService.hasPermission(Resource(resourceType.name, ResourceId(resourceId)), ResourceAction(action), userInfo).map { hasPermission =>
                        StatusCodes.OK -> JsBoolean(hasPermission)
                      })
                    }
                  }
                }
              } ~
              pathPrefix("policies") {
                pathEndOrSingleSlash {
                  get {
                    requireAction(resource, SamResourceActions.readPolicies, userInfo) {
                      complete(resourceService.listResourcePolicies(Resource(resourceType.name, ResourceId(resourceId))).map { response =>
                        StatusCodes.OK -> response
                      })
                    }
                  }
                } ~
                  pathPrefix(Segment) { policyName =>
                    val resourceAndPolicyName = ResourceAndPolicyName(Resource(resourceType.name, ResourceId(resourceId)), AccessPolicyName(policyName))
                    pathEndOrSingleSlash {
                      get {
                        requireOneOfAction(resource, Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(resourceAndPolicyName.accessPolicyName)), userInfo) {
                          complete(resourceService.loadResourcePolicy(resourceAndPolicyName).map {
                            case Some(response) => StatusCodes.OK -> response
                            case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy not found"))
                          })
                        }
                      } ~
                      put {
                        requireAction(resource, SamResourceActions.alterPolicies, userInfo) {
                          entity(as[AccessPolicyMembership]) { membershipUpdate =>
                            complete(resourceService.overwritePolicy(resourceType, AccessPolicyName(policyName), Resource(resourceType.name, ResourceId(resourceId)), membershipUpdate).map(_ => StatusCodes.Created))
                          }
                        }
                      }
                    } ~
                    pathPrefix("memberEmails") {
                      pathPrefix(Segment) { email =>
                        withSubject(WorkbenchEmail(email)) { subject =>
                          pathEndOrSingleSlash {
                            requireOneOfAction(resource, Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(resourceAndPolicyName.accessPolicyName)), userInfo) {
                              put {
                                complete(resourceService.addSubjectToPolicy(resourceAndPolicyName, subject).map(_ => StatusCodes.NoContent))
                              } ~
                              delete {
                                complete(resourceService.removeSubjectFromPolicy(resourceAndPolicyName, subject).map(_ => StatusCodes.NoContent))
                              }
                            }
                          }
                        }
                      }
                    }
                  }
              } ~
              pathPrefix("roles") {
                pathEndOrSingleSlash {
                  get {
                    complete(resourceService.listUserResourceRoles(Resource(resourceType.name, ResourceId(resourceId)), userInfo).map { roles =>
                      StatusCodes.OK -> roles
                    })
                  }
                }
              }
            }
          }
        }
      }
    }
}
