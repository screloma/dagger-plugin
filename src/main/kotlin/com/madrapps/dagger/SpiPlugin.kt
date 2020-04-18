package com.madrapps.dagger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.madrapps.dagger.core.NodeType
import com.madrapps.dagger.services.log
import com.madrapps.dagger.services.service
import com.madrapps.dagger.core.toNodeType
import com.madrapps.dagger.toolwindow.DaggerNode
import com.sun.tools.javac.code.Attribute
import com.sun.tools.javac.code.Symbol
import com.sun.tools.javac.code.Type
import dagger.MapKey
import dagger.model.Binding
import dagger.model.BindingGraph
import dagger.model.BindingKind
import dagger.model.DependencyRequest
import dagger.multibindings.ClassKey
import dagger.multibindings.IntKey
import dagger.multibindings.LongKey
import dagger.multibindings.StringKey
import dagger.spi.BindingGraphPlugin
import dagger.spi.DiagnosticReporter
import javax.inject.Qualifier
import javax.lang.model.element.Element
import javax.swing.tree.DefaultMutableTreeNode

class SpiPlugin(private val project: Project) : BindingGraphPlugin {

    private var firstNodeCreated = false

    override fun visitGraph(
        bindingGraph: BindingGraph,
        diagnosticReporter: DiagnosticReporter
    ) {
        val rootComponentNode = bindingGraph.rootComponentNode()
        project.log("------------------------------------------------------------")
        project.log("RootComponent - $rootComponentNode")
        val componentNodes = bindingGraph.componentNodes()
        project.log("ComponentNodes - ${componentNodes.size}")
        project.log("------------------------------------------------------------\n")

        addBindings(bindingGraph)
    }


    private fun addBindings(bindingGraph: BindingGraph) {

        ApplicationManager.getApplication().runReadAction {
            val rootComponentNode = bindingGraph.rootComponentNode()
            val componentNodes = bindingGraph.componentNodes()

            componentNodes.forEach {
                val componentNode =
                    DaggerNode(project, it.name, it.toPsiClass(project)!!, null, "", it.toNodeType())
                it.entryPoints().forEach {
                    addNodes(it, bindingGraph, componentNode, null, true)
                }
                rootNode.add(componentNode)
                project.service.treeModel.reload()
            }
        }
    }

    private fun addNodes(
        dr: DependencyRequest,
        bindingGraph: BindingGraph,
        parentNode: DaggerNode,
        parentBinding: Binding?,
        isEntryPoint: Boolean
    ) {
        var currentNode = parentNode
        val key = dr.key()
        val binding = bindingGraph.bindings(key).first()
        if (binding.kind().isMultibinding) {
            val classType = dr.key().type() as? Type.ClassType
            if (classType != null) {
                val name = classType.presentableName()
                val psiElement = dr.requestElement().orNull()?.toPsiElement(project)!!
                currentNode = DaggerNode(
                    project,
                    name,
                    psiElement,
                    if (isEntryPoint) dr.sourceMethod() else null,
                    key.toString(),
                    binding.kind().toNodeType()
                )
                parentNode.add(currentNode)
            }
        } else {
            binding.bindingElement().ifPresent { element ->
                val daggerNode = dr.createNode(
                    element,
                    isEntryPoint,
                    parentBinding,
                    key.toString(),
                    binding.kind().toNodeType()
                )
                if (daggerNode != null) {
                    currentNode = daggerNode
                    parentNode.add(currentNode)
                }
            }
        }
        if (!parentNode.isVisitedAlready(key.toString())) { // to avoid circular dependencies causing stackOverFlow
            binding.dependencies().filterNot { it.key().toString().startsWith("dagger.android.") }.forEach {
                addNodes(it, bindingGraph, currentNode, binding, false)
            }
        }
    }

    private fun DependencyRequest.createNode(
        element: Element,
        isEntryPoint: Boolean,
        parentBinding: Binding?,
        key: String,
        nodeType: NodeType
    ): DaggerNode? {
        val name: String
        val psiElement: PsiElement
        when (element) {
            is Symbol.ClassSymbol -> {
                psiElement = element.toPsiClass(project)!!
                name = requestElement().orNull()?.name() ?: psiElement.name ?: element.simpleName.toString()
            }
            is Symbol.VarSymbol -> {
                psiElement = element.toPsiParameter(project)!!
                name = requestElement().orNull()?.name() ?: psiElement.type.presentableText
            }
            is Symbol.MethodSymbol -> {
                psiElement = element.toPsiMethod(project)!!
                var temp = requestElement().orNull()?.name() ?: if (psiElement.isConstructor) {
                    psiElement.name
                } else {
                    psiElement.returnType?.presentableText ?: "NULL"
                }
                if (parentBinding?.kind() == BindingKind.MULTIBOUND_MAP) {
                    val snd = element.annotationMirrors.find {
                        keys.contains((it.annotationType as Type.ClassType).tsym.qualifiedName.toString()) ||
                                it.type.tsym.annotationMirrors.find { (it.annotationType as Type.ClassType).tsym.qualifiedName.toString() == MapKey::class.java.name } != null
                    }?.values?.first()?.snd
                    val sndText = when (snd) {
                        is Attribute.Enum -> "${snd.type.tsym.simpleName}.${snd.value.simpleName}"
                        is Attribute.Class -> snd.classType.tsym.simpleName
                        else -> snd.toString()
                    }
                    temp += " [$sndText]"
                }
                val snd = element.annotationMirrors.find {
                    it.type.tsym.annotationMirrors.find { (it.annotationType as Type.ClassType).tsym.qualifiedName.toString() == Qualifier::class.java.name } != null
                }
                if (snd != null) {
                    temp += "[${snd.type.tsym.simpleName}]"
                }
                name = temp
            }
            else -> return null
        }
        val sourceMethod = if (isEntryPoint) sourceMethod() else null
        return DaggerNode(
            project,
            name,
            psiElement,
            sourceMethod,
            key,
            nodeType
        )
    }

    private val rootNode: DefaultMutableTreeNode
        get() {
            var root = project.service.treeModel.root
            if (!firstNodeCreated) {
                project.service.reset()
                firstNodeCreated = true
                root = DefaultMutableTreeNode("")
                project.service.treeModel.setRoot(root)
            }
            return root as DefaultMutableTreeNode
        }

    private val keys = listOf<String>(
        StringKey::class.java.name,
        IntKey::class.java.name,
        LongKey::class.java.name,
        ClassKey::class.java.name
    )
}