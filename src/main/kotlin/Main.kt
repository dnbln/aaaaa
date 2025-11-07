package com.dysaster

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ASM4
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.io.FileOutputStream
import java.io.FileReader
import java.util.jar.JarFile
import java.util.zip.ZipOutputStream

class ExplorerClassVisitor(val jars: List<JarFile>, val wq: MutableList<String>) : ClassVisitor(ASM4) {
    internal sealed class MemberDescriptor {
        data class FieldDescriptor(val name: String) : MemberDescriptor()
        data class MethodDescriptor(val name: String, val signature: String) : MemberDescriptor()
        data class InnerClassDescriptor(val name: String) : MemberDescriptor()
    }

    var depsMode: Boolean = false
    val depsWq: MutableList<String> = mutableListOf()

    val classes = mutableMapOf<String, ClassWriter>() // map of class name to class writer in outJar
    fun classForName(name: String): ClassWriter = classes.getOrPut(name) { ClassWriter(0) }

    internal inner class SigVisitor : SignatureVisitor(ASM4) {
        override fun visitClassType(name: String?) {
            super.visitClassType(name)

            if (name == null) return

            if (this@ExplorerClassVisitor.visited.contains(name)) {
                return
            }

            this@ExplorerClassVisitor.depsWq.add(name)
        }
    }

    private var currentClass: ClassWriter? = null
    private var lookingForMember: MemberDescriptor? = null
    private val visited: MutableList<String> = mutableListOf()

    override fun visitInnerClass(name: String?, outerName: String?, innerName: String?, access: Int) {
        when (val member = lookingForMember) {
            is MemberDescriptor.InnerClassDescriptor -> {
                if (member.name == innerName) {
                    currentClass!!.visitInnerClass(name, outerName, innerName, access)
                }
            }

            null -> {
                if (depsMode) return

                currentClass!!.visitInnerClass(name, outerName, innerName, access)
            }

            else -> {}
        }

        super.visitInnerClass(name, outerName, innerName, access)
    }

    class AnnotVisitor(val del: AnnotationVisitor) : AnnotationVisitor(ASM4) {
        override fun visit(name: String?, value: Any?) {
            del.visit(name, value)
        }

        override fun visitAnnotation(name: String?, desc: String?): AnnotVisitor? {
            return del.visitAnnotation(name, desc)?.let(::AnnotVisitor)
        }

        override fun visitArray(name: String?): AnnotVisitor? {
            return del.visitArray(name)?.let(::AnnotVisitor)
        }

        override fun visitEnd() {
            del.visitEnd()
        }

        override fun visitEnum(name: String?, desc: String?, value: String?) {
            del.visitEnum(name, desc, value)
        }
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String?>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        currentClass!!.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitOuterClass(owner: String?, name: String?, desc: String?) {
        super.visitOuterClass(owner, name, desc)
        currentClass!!.visitOuterClass(owner, name, desc)
    }

    override fun visitAnnotation(desc: String?, visible: Boolean): AnnotVisitor? {
        return currentClass!!.visitAnnotation(desc, visible)?.let(::AnnotVisitor)
    }

    override fun visitAttribute(attr: Attribute?) {
        currentClass!!.visitAttribute(attr)
    }

    override fun visitField(access: Int, name: String?, desc: String?, signature: String?, value: Any?): FieldVisitor? {
        return when (val member = lookingForMember) {
            is MemberDescriptor.FieldDescriptor -> {
                if (member.name == name) {
                    val sig = signature ?: desc
                    if (sig != null) {
                        val sr = SignatureReader(sig)
                        sr.acceptType(SigVisitor())
                    }

                    currentClass!!.visitField(access, name, desc, signature, value)
                } else {
                    null
                }
            }

            null -> {
                if (depsMode) return null

                val sig = signature ?: desc
                if (sig != null) {
                    val sr = SignatureReader(sig)
                    sr.acceptType(SigVisitor())
                }
                currentClass!!.visitField(access, name, desc, signature, value)
            }

            else -> {
                null
            }
        }
    }

    class MVisitor(val del: MethodVisitor) : MethodVisitor(ASM4) {
        override fun visitAnnotationDefault(): AnnotationVisitor? {
            return del.visitAnnotationDefault()?.let(::AnnotVisitor)
        }

        override fun visitCode() {
            // replace all method bodies with `throw new RuntimeException();`

            // NEW java/lang/IllegalArgumentException
            del.visitTypeInsn(NEW, "java/lang/RuntimeException");

            // DUP
            del.visitInsn(DUP);

            // INVOKESPECIAL java/lang/RuntimeException.<init>()V
            del.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/RuntimeException",
                "<init>",
                "()V",
                false
            );

            // ATHROW
            del.visitInsn(ATHROW);
        }

        override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor? {
            return del.visitAnnotation(desc, visible)?.let(::AnnotVisitor)
        }

        override fun visitInsn(opcode: Int) {
        }

        override fun visitFrame(type: Int, nLocal: Int, local: Array<out Any?>?, nStack: Int, stack: Array<out Any?>?) {
        }
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        desc: String?,
        signature: String?,
        exceptions: Array<out String?>?
    ): MVisitor? {
        return when (val member = lookingForMember) {
            is MemberDescriptor.MethodDescriptor -> {
                val sig = signature ?: desc
                if (member.name == name && member.signature == sig) {
                    val sr = SignatureReader(sig)
                    sr.accept(SigVisitor())
                    currentClass!!.visitMethod(access, name, desc, signature, exceptions)?.let(::MVisitor)
                } else {
                    null
                }
            }

            null -> {
                if (depsMode) return null

                val sig = signature ?: desc
                val sr = SignatureReader(sig)
                sr.accept(SigVisitor())
                currentClass!!.visitMethod(access, name, desc, signature, exceptions)?.let(::MVisitor)
            }

            else -> {
                null
            }
        }
    }

    fun run(wqItem: String) {
        if (visited.contains(wqItem)) return
        visited.add(wqItem)
        find(wqItem)
    }

    fun run() {
        while (wq.isNotEmpty()) {
            val wqItem = wq.removeAt(0)
            run(wqItem)
        }

        depsMode = true
        while (depsWq.isNotEmpty()) {
            val wqItem = depsWq.removeAt(0)
            run(wqItem)
        }
    }

    fun visitInJar(jar: JarFile, className: String): Boolean {
        val effectiveClassName = className.replace(".", "/") + ".class"
        val entry = jar.getJarEntry(effectiveClassName) ?: return false

        println("Entry $effectiveClassName found in JAR ${jar.name}")

        val inputStream = jar.getInputStream(entry)
        val cr = ClassReader(inputStream)
        currentClass = classForName(effectiveClassName)
        cr.accept(this, 0)

        return true
    }

    fun visitAllJars(className: String): JarFile? {
        for (jar in jars) {
            if (visitInJar(jar, className))
                return jar
        }

        return null
    }

    fun find(toFind: String) {
        if (toFind.contains("#")) {
            val parts = toFind.split("#")
            val className = parts[0]
            val memberName = parts[1]
            if (memberName.contains("(")) {
                val methodName = parts[1].split("(")[0]
                val sig = "(" + parts[1].substringAfter("(")
                lookingForMember = MemberDescriptor.MethodDescriptor(methodName, sig)
            } else {
                lookingForMember = MemberDescriptor.FieldDescriptor(memberName)
            }

            visitAllJars(className)

            lookingForMember = null
        } else if (toFind.contains("$")) {
            // outer class
            val outerClassName = toFind.substringBefore("$")
            val className = toFind.substringAfter("$")

            lookingForMember = MemberDescriptor.InnerClassDescriptor(className)
            val jar = visitAllJars(outerClassName)
            lookingForMember = null

            if (jar != null) {
                // inner class
                visitInJar(jar, toFind)
            }
        } else {
            visitAllJars(toFind)
        }
    }

    fun compileJar(outJar: ZipOutputStream) {
        for ((className, classWriter) in classes) {
            val entry = java.util.zip.ZipEntry(className)
            outJar.putNextEntry(entry)
            outJar.write(classWriter.toByteArray())
            outJar.closeEntry()
        }
    }
}

fun main(args: Array<String>) {
    val jars = mutableListOf<JarFile>()
    val input = mutableListOf<String>()
    for (arg in args) {
        if (arg.endsWith(".jar")) {
            jars.add(JarFile(arg))
        } else {
            FileReader(arg).useLines {
                it.filter { s -> !s.startsWith("#") && s.trim() != "" }.forEach(input::add)
            }
        }
    }

//    println("JARs to process: $jars")

    val cv = ExplorerClassVisitor(jars, input)

    cv.run()

    ZipOutputStream(FileOutputStream("output.jar")).use(cv::compileJar)
}