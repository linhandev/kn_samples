package org.cpf.kotlin.capi

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create

/**
 * Registers [CapiKlibPublishing] so modules can call
 * `extensions.getByType<CapiKlibPublishing>().configure(...)`.
 */
class CapiKlibPublishingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("capiKlibPublishing", CapiKlibPublishing::class.java)
    }
}
