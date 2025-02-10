package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import org.jetbrains.kotlin.test.helper.asPathWithoutAllExtensions
import org.jetbrains.kotlin.test.helper.nameWithoutAllExtensions
import java.io.File

private val testNameReplacementRegex = "[.-]".toRegex()

fun VirtualFile.collectTestMethods(project: Project): List<PsiMethod> {
    val cache = PsiShortNamesCache.getInstance(project)
    val targetMethodName = "test${nameWithoutAllExtensions.replaceFirstChar { it.uppercaseChar() }.replace(testNameReplacementRegex, "_")}"
    val methods = cache.getMethodsByName(targetMethodName, GlobalSearchScope.allScope(project))
        .filter { it.hasAnnotation("org.jetbrains.kotlin.test.TestMetadata") }
    val truePathWithoutAllExtensions = File(this.path).absolutePath.asPathWithoutAllExtensions
    val foundMethods: MutableList<PsiMethod> = ArrayList()
    for (method in methods) {
        val psiClass = method.containingClass

        var currentPsiClass = psiClass
        var testMetaData: String? = null
        var testDataPath: String? = null
        while (currentPsiClass != null) {
            testMetaData = testMetaData
                ?: if (currentPsiClass == psiClass) currentPsiClass.extractTestMetadataValue()
                else null
            val localTestDataPath: String? = currentPsiClass.extractTestDataPath(project)
            testDataPath = localTestDataPath ?: testDataPath
            val containingClass = currentPsiClass.containingClass
            currentPsiClass = containingClass
        }

        val methodPathPart = method.extractTestMetadataValue() ?: continue

        val methodPathComponents = buildList {
            add(methodPathPart)
            testMetaData?.takeIf(String::isNotEmpty)?.let(::add)
            testDataPath?.takeIf(String::isNotEmpty)?.let(::add)
            if (testDataPath == null) add(project.basePath!!)
        }
        val methodPath = File(methodPathComponents.reversed().joinToString("/"))
            .canonicalFile.absolutePath
        if (methodPath.asPathWithoutAllExtensions == truePathWithoutAllExtensions) {
            foundMethods.add(method)
        }
    }
    return foundMethods
}

fun PsiModifierListOwner?.extractTestMetadataValue(): String? {
    return annotationValue("org.jetbrains.kotlin.test.TestMetadata")
}

private fun PsiModifierListOwner?.extractTestDataPath(project: Project): String? {
    var path = annotationValue("com.intellij.testFramework.TestDataPath") ?: return null
    if (path.contains("\$CONTENT_ROOT")) {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val file = this?.containingFile?.virtualFile ?: return null
        val contentRoot = fileIndex.getContentRootForFile(file) ?: return null
        path = path.replace("\$CONTENT_ROOT", contentRoot.path)
    }
    if (path.contains("\$PROJECT_ROOT")) {
        val baseDir = project.basePath ?: return null
        path = path.replace("\$PROJECT_ROOT", baseDir)
    }
    return path
}

private fun PsiModifierListOwner?.annotationValue(name: String): String? {
    return this?.getAnnotation(name)
        ?.parameterList
        ?.attributes
        ?.firstOrNull()
        ?.literalValue
}