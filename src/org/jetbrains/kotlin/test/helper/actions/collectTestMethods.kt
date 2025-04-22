package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import org.jetbrains.kotlin.test.helper.TestDataType
import org.jetbrains.kotlin.test.helper.asPathWithoutAllExtensions
import org.jetbrains.kotlin.test.helper.getTestDataType
import org.jetbrains.kotlin.test.helper.nameWithoutAllExtensions
import java.io.File

private val testNameReplacementRegex = "[.-]".toRegex()

fun VirtualFile.collectTestMethodsIfTestData(project: Project): List<PsiNameIdentifierOwner> {
    val testDataType = getTestDataType(project) ?: return emptyList()

    val normalizedFile = if (isFile && testDataType == TestDataType.Directory) parent else this
    val normalizedName = normalizedFile.nameWithoutAllExtensions.replaceFirstChar { it.uppercaseChar() }
        .replace(testNameReplacementRegex, "_")
    val truePathWithoutAllExtensions = File(normalizedFile.path).absolutePath.asPathWithoutAllExtensions

    val cache = PsiShortNamesCache.getInstance(project)

    return if (testDataType == TestDataType.DirectoryOfFiles) {
        val classes = cache.getClassesByName(normalizedName, GlobalSearchScope.allScope(project))
        classes.filter { it ->
            val (testMetaData, testDataPath) = it.extractClassTestMetadata(project)
            buildPath(null, testMetaData, testDataPath, project) == truePathWithoutAllExtensions
        }
    } else {
        val methods = cache.getMethodsByName("test$normalizedName", GlobalSearchScope.allScope(project))
            .filter { it.hasAnnotation("org.jetbrains.kotlin.test.TestMetadata") }
        val truePathWithoutAllExtensions = File(normalizedFile.path).absolutePath.asPathWithoutAllExtensions

        methods.filter {
            val psiClass = it.containingClass
            val (testMetaData, testDataPath) = psiClass.extractClassTestMetadata(project)
            val methodPathPart = it.extractTestMetadataValue() ?: return@filter false
            buildPath(methodPathPart, testMetaData, testDataPath, project) == truePathWithoutAllExtensions
        }
    }
}

private fun buildPath(
    methodPathPart: String?,
    testMetaData: String?,
    testDataPath: String?,
    project: Project
): String {
    val methodPathComponents = buildList {
        methodPathPart?.let(::add)
        testMetaData?.takeIf(String::isNotEmpty)?.let(::add)
        testDataPath?.takeIf(String::isNotEmpty)?.let(::add)
        if (testDataPath == null) add(project.basePath!!)
    }
    return File(methodPathComponents.reversed().joinToString("/"))
        .canonicalFile.absolutePath.asPathWithoutAllExtensions
}

private fun PsiClass?.extractClassTestMetadata(project: Project): Pair<String?, String?> {
    var currentPsiClass = this
    var testMetaData: String? = null
    var testDataPath: String? = null
    while (currentPsiClass != null) {
        testMetaData = testMetaData
            ?: if (currentPsiClass == this) currentPsiClass.extractTestMetadataValue()
            else null
        val localTestDataPath: String? = currentPsiClass.extractTestDataPath(project)
        testDataPath = localTestDataPath ?: testDataPath
        val containingClass = currentPsiClass.containingClass
        currentPsiClass = containingClass
    }
    return Pair(testMetaData, testDataPath)
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