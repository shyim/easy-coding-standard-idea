package de.shyim.easycodingstandardidea.vcs

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.ui.BooleanCommitOption
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CheckinMetaHandler
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsFileUtil

class EasyCodingStandardHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return EasyCodingStandardHandler(panel.project, panel)
    }
}

class EasyCodingStandardHandler(val myProject: Project, val panel: CheckinProjectPanel) : CheckinHandler(), CheckinMetaHandler {
    var REFORMAT_BEFORE_COMMIT = true

    private fun havePhpFiles(): Boolean {
        var foundPhpFile = false

        this.panel.virtualFiles.forEach {
            if (it.fileType.name == "PHP") {
                foundPhpFile = true
            }
        }

        return foundPhpFile
    }

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent? {
        if (!this.havePhpFiles()) {
            this.REFORMAT_BEFORE_COMMIT = false
            return null
        }

        this.REFORMAT_BEFORE_COMMIT = true

        return BooleanCommitOption(
            panel,
            "Reformat using ECS",
            false,
            this::REFORMAT_BEFORE_COMMIT
        )
    }

    override fun runCheckinHandlers(runnable: Runnable) {
        val saveAndContinue = {
            FileDocumentManager.getInstance().saveAllDocuments()
            runnable.run()
        }

        if (this::REFORMAT_BEFORE_COMMIT.get() && !DumbService.isDumb(myProject)) {
            fixFiles(myProject, panel.virtualFiles, runnable)
        } else {
            saveAndContinue()
        }
    }

    companion object {
        fun fixFiles(project: Project, virtualFiles: MutableCollection<VirtualFile>, myPostRunnable: Runnable) {
            FileDocumentManager.getInstance().saveAllDocuments()
            val isSuccess = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Boolean, RuntimeException> {
                    val projectBasePath: VirtualFile = getBasePathAsVirtualFile(project) ?: return@ThrowableComputable false
                    val files: String = virtualFiles
                        .filter { it.fileType.name == "PHP" }
                        .flatMap { f: VirtualFile? -> VfsUtil.collectChildrenRecursively(f!!) }
                        .mapNotNull { f: VirtualFile? -> VcsFileUtil.getRelativeFilePath(f, projectBasePath) }
                        .toList()
                        .joinToString(" ")


                    val commandLine = GeneralCommandLine("./vendor/bin/ecs", "check", "--fix", *(arrayOf(files)))

                    commandLine.withWorkDirectory(project.basePath)
                    val handler = CapturingProcessHandler(commandLine)
                    return@ThrowableComputable handler.runProcess().exitCode == 0
                },
                "Formatting files",
                true,
                project
            )

            if (isSuccess) {
                myPostRunnable.run()
            }
        }

        private fun getBasePathAsVirtualFile(project: Project): VirtualFile? {
            val basePath = project.basePath
            return if (basePath == null) null else LocalFileSystem.getInstance().findFileByPath(basePath)
        }
    }
}