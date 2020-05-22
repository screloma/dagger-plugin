package com.madrapps.dagger.validation

import com.intellij.psi.PsiElement
import com.madrapps.dagger.utils.isBinds
import com.madrapps.dagger.utils.psiIdentifier
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingDeclaration
import org.jetbrains.uast.toUElement

object BindsProblem : Problem {

    override fun isError(element: PsiElement): List<Problem.Error> {
        val annotation = element.toUElement()
        if (annotation is UAnnotation && annotation.isBinds) {
            val declaration = annotation.getContainingDeclaration() ?: return emptyList()
            val range = annotation.psiIdentifier
            return when (declaration) {
                is UMethod -> validateMethod(declaration, range)
                else -> emptyList()
            }
        }
        return emptyList()
    }

    private fun validateMethod(method: UMethod, range: PsiElement): List<Problem.Error> {
        return mutableListOf<Problem.Error>().apply {
            this += method.validateModuleClass(
                range, "@Binds methods can only be present within a @module or @ProducerModule"
            )
            this += method.validateTypeParameter(range, "@Binds methods may not have type parameters")
        }
    }
}
