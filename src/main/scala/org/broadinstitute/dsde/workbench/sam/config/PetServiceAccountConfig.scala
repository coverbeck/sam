package org.broadinstitute.dsde.workbench.sam.config

import org.broadinstitute.dsde.workbench.model.WorkbenchUserEmail

/**
  * Pet Service Account configuration.
  * @param googleProject The project in which to create pet service accounts.
  * @param serviceAccountActors Set of emails for which to grant the Service Account Actor role
  *                             on pet service accounts. Generally used for cases where other
  *                             application service accounts need to impersonate as the pet service
  *                             account.
  */
case class PetServiceAccountConfig(googleProject: String, serviceAccountActors: Set[WorkbenchUserEmail])