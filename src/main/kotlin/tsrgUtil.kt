import java.io.File
import java.util.*
import kotlin.contracts.contract

/**
 * Various utility functions for dealing with TSRG.
 * This code is super ugly.
 *
 * @author phase
 */
object TSrgUtil {

    // these classes are using data based on the TSRG format, not the SRG format

    data class Clazz(
        var obf: String, var deobf: String, var other: List<String> = Collections.emptyList(),
        val fields: MutableList<Field> = mutableListOf(),
        val methods: MutableList<Method> = mutableListOf(),
    ) {
        constructor(obf: String, deobf: String, fields: MutableList<Field>): this(obf, deobf, Collections.emptyList(), fields)
        constructor(obf: String, deobf: String, fields: MutableList<Field>, methods: MutableList<Method>): this(obf, deobf, Collections.emptyList(), fields, methods)

        override fun toString(): String = "$obf $deobf"
    }

    data class Field(var obf: String, var deobf: String, var other: List<String> = Collections.emptyList()) {
        override fun toString(): String = "$obf $deobf"
    }

    data class Method(var obf: String, var obfSig: String, var deobf: String, var other: List<String> = Collections.emptyList()) {

        override fun toString(): String = "$obf $obfSig $deobf"

        fun getDeobfSig(classNames: Map<String, String>): String {
            // find what classes need to be replaced in the obfuscated string
            val classesToReplace = mutableListOf<String>()
            var buffer = ""
            var state = false
            obfSig.forEach {
                when (it) {
                    'L' -> {
                        if (!state) {
                            buffer = ""
                            state = true
                        } else {
                            // the obfSig contains the letter L, like "GLX"
                            buffer += it
                        }
                    }
                    ';' -> {
                        classesToReplace.add(buffer)
                        state = false
                    }
                    else -> {
                        if (state) {
                            buffer += it
                        }
                    }
                }
            }

            // replace the obfuscated classes
            var deobfSig = obfSig
            classesToReplace.forEach { obfClassName ->
                if (classNames.containsKey(obfClassName)) {
                    deobfSig = deobfSig.replace("L$obfClassName;", "L${classNames[obfClassName]!!};")
                }
            }
            return deobfSig
        }
    }

    fun parseTSrg(lines: List<String>): List<Clazz> {
        val classes = mutableListOf<Clazz>()
        var currentClass: Clazz? = null

        // parse the lines
        lines.forEachIndexed { index, line ->
            if (line.startsWith("#") || line.trim().isEmpty()) { // comment
            } else if (line.startsWith("\t\t") || line.startsWith("  ")) { //some random forge extra information.
                //todo: add support for this?
            } else if (line.startsWith("\t") || line.startsWith(" ")) {
                if (currentClass == null) throw RuntimeException("Parse error on line $index: no class\n$line")
                val l = line.trim()
                val parts = l.split(" ")

                if (parts.size < 2)
                    throw RuntimeException("Parse error on line $index: class definition has too little parts\n$line")
                
                if (parts[1].contains("(") && parts[1].contains(")")) {
                    if (parts.size < 3)
                        throw RuntimeException("Parse error on line $index: class definition has too little parts\n$line")

                    // method
                    val obf = parts[0]
                    val obfSig = parts[1]
                    val deobf = parts[2]
                    var other: List<String> = Collections.emptyList()
                    if (parts.size > 3) {
                        other = parts.subList(4, parts.size)
                    }
                    currentClass!!.methods.add(Method(obf, obfSig, deobf, other))
                }else {
                    var other: List<String> = Collections.emptyList()
                    if (parts.size > 2) {
                        other = parts.subList(3, parts.size)
                    }
                    // field
                    val obf = parts[0]
                    val deobf = parts[1]
                    currentClass!!.fields.add(Field(obf, deobf, other))
                }
            } else if (line.contains(" ")) {
                currentClass?.let { classes.add(it) }
                val parts = line.split(" ")
                if (parts.size < 2)
                    throw RuntimeException("Parse error on line $index: class definition has too little parts\n$line")
                var other: List<String> = Collections.emptyList()
                if (parts.size > 2) {
                    other = parts.subList(3, parts.size)
                }
                currentClass = Clazz(parts[0], parts[1], other)
            }
        }
        currentClass?.let {
            if (!classes.contains(it)) classes.add(it)
        }
        return classes
    }

    fun toSrg(classes: List<Clazz>, srgFile: File) {
        val classNames = classes.map { it.obf to it.deobf }.toMap()
        val output = StringBuilder()
        // write the classes out in SRG format
        classes.forEach { clazz ->
            if (clazz.obf != clazz.deobf) {
                output.append("CL: ${clazz.obf} ${clazz.deobf}\n")
            }

            clazz.fields.forEach { field ->
                output.append("FD: ${clazz.obf}/${field.obf} ${clazz.deobf}/${field.deobf}\n")
            }

            clazz.methods.forEach { method ->
                val deobfSig = method.getDeobfSig(classNames)
                output.append(
                    "MD: ${clazz.obf}/${method.obf} ${method.obfSig} " +
                            "${clazz.deobf}/${method.deobf} $deobfSig\n"
                )
            }
        }
        srgFile.createNewFile();
        srgFile.writeText(output.toString().split("\n").sorted().filter { it.isNotEmpty() }.joinToString("\n"))
    }

    fun toSrg(tsrgFile: File, srgFile: File): List<Clazz> {
        // checks
        srgFile.getParentFile().mkdirs()
        if (!(srgFile.exists())) srgFile.createNewFile()
        if (srgFile.exists() && !srgFile.isFile) throw RuntimeException("srg path is not a file: $srgFile")
        if (!tsrgFile.exists() || !tsrgFile.isFile) throw RuntimeException("tsrg file not found: $tsrgFile")

        val classes = parseTSrg(tsrgFile.readLines())

        toSrg(classes, srgFile)
        return classes
    }

    fun fromSrg(srgFile: File, tsrgFile: File) {
        // checks
        if (!(tsrgFile.exists())) tsrgFile.createNewFile()
        if (tsrgFile.exists() && !tsrgFile.isFile) throw RuntimeException("tsrg path is not a file: $tsrgFile")
        if (!srgFile.exists() || !srgFile.isFile) throw RuntimeException("srg file not found: $srgFile")

        val lines = srgFile.readLines()
        val classes = HashMap<String, Clazz>(MAPPING_CLASS_COUNT_APPROXIMATION) //Todo: Let's hope that obf names are always unique

        lines.forEach { line ->
            when (true) {
                line.startsWith("CL: ") -> {
                    val l = line.substring(4, line.length)
                    val parts = l.split(" ")
                    val obf = parts[0]
                    val deobf = parts[1]
                    classes.computeIfAbsent(obf) {
                        Clazz(it, deobf)
                    }
                }
                line.startsWith("FD: ") -> {
                    val l = line.substring(4, line.length)
                    val parts = l.split(" ")

                    // obf part
                    val p0 = parts[0]
                    val p0s = p0.lastIndexOf('/')
                    val obfClass = p0.substring(0, p0s)
                    val obf = p0.substring(p0s + 1, p0.length)

                    // deobf part
                    val p1 = parts[1]
                    val p1s = p1.lastIndexOf('/')
                    val deobfClass = p1.substring(0, p1s)
                    val deobf = p1.substring(p1s + 1, p1.length)

                    classes.getOrDefault(obfClass, Clazz(obfClass, deobfClass)).fields.add(Field(obf, deobf))
                }
                line.startsWith("MD: ") -> {
                    val l = line.substring(4, line.length)
                    val parts = l.split(" ")

                    // obf part
                    val p0 = parts[0]
                    val p0s = p0.lastIndexOf('/')
                    val obfClass = p0.substring(0, p0s)
                    val obf = p0.substring(p0s + 1, p0.length)

                    val obfSig = parts[1]

                    // deobf part
                    val p2 = parts[2]
                    val p2s = p2.lastIndexOf('/')
                    val deobfClass = p2.substring(0, p2s)
                    val deobf = p2.substring(p2s + 1, p2.length)

                    classes.getOrDefault(obfClass, Clazz(obfClass, deobfClass)).methods.add(Method(obf, obfSig, deobf))
                }

                else -> {}
            }
        }

        val output = StringBuilder()
        classes.values.forEach { clazz ->
            output.append("$clazz\n")
            clazz.fields.forEach {
                output.append("\t$it\n")
            }
            clazz.methods.forEach {
                output.append("\t$it\n")
            }
        }
        tsrgFile.writeText(output.toString())
    }

}

object MappingsGenerator {

    private data class ClassMapping(val deobf: String, var clientObf: String? = null, var serverObf: String? = null) {
        override fun toString(): String = "$deobf (client: $clientObf) (server: $serverObf)"
    }

    private data class FieldMapping(
        val deobfField: String,
        val deobfClass: String,
        var clientObfTotal: String? = null,
        var serverObfTotal: String? = null
    ) {
        override fun toString(): String = "$deobfClass.$deobfField (client: $clientObfTotal) (server: $serverObfTotal)"
    }

    private inline fun unquote(s: String): String = s.substring(1, s.length - 1)

    /**
     * @param classFile CSV file containing MCP v4.3 mappings
     * @return Pair(serverOnlyClassesObf, map from serverObf to clientObf)
     */
    fun generateClassMappings(classFile: File): Pair<List<String>, Map<String, String>> {
        val classNames = classFile.readLines().toMutableList()
        classNames.removeAt(0) // remove column definition
        val classes = mutableListOf<ClassMapping>()

        classNames.forEach {
            val parts = it.split(",")
            val deobf = unquote(parts[0])
            val obf = unquote(parts[1])
            val isClient = unquote(parts[4]).toInt() == 0

            var foundClass = false
            classes.forEach {
                if (it.deobf == deobf && (it.clientObf == null || it.serverObf == null)) {
                    foundClass = true
                    if (isClient) it.clientObf = obf else it.serverObf = obf
                }
            }

            if (!foundClass) {
                if (isClient) {
                    classes.add(ClassMapping(deobf, obf, null))
                } else {
                    classes.add(ClassMapping(deobf, null, obf))
                }
            }

        }

        return Pair(
            classes.filter { it.clientObf == null && it.serverObf != null }.map { it.serverObf!! },
            classes.filter { it.clientObf != null && it.serverObf != null }.map { it.serverObf!! to it.clientObf!! }.toMap()
        )
    }

    /**
     * @param fieldFile CSV file containing MCP v4.3 mappings
     * @return TODO
     */
    fun generateFieldMappings(fieldFile: File): Map<String, String> {
        val fieldNames = fieldFile.readLines().toMutableList()
        fieldNames.removeAt(0) // remove column definition
        val fields = mutableListOf<FieldMapping>()

        fieldNames.forEach {
            val parts = it.split(",")
            val deobfField = unquote(parts[1])
            val deobfClass = unquote(parts[5])
            val obfField = unquote(parts[2])
            val obfClass = unquote(parts[6])
            val obf = "$obfClass.$obfField"
            val isClient = unquote(parts[8]).toInt() == 0

            var foundField = false
            fields.forEach {
                if (it.deobfClass == deobfClass && it.deobfField == deobfField) {
                    foundField = true
                    if (isClient) it.clientObfTotal = obf else it.serverObfTotal = obf
                }
            }

            if (!foundField) {
                if (isClient) {
                    fields.add(FieldMapping(deobfField, deobfClass, obf, null))
                } else {
                    fields.add(FieldMapping(deobfField, deobfClass, null, obf))
                }
            }
        }

        return fields.filter {
            it.clientObfTotal != null && it.serverObfTotal != null
                    && it.clientObfTotal!!.split(".")[1] !=
                    it.serverObfTotal!!.split(".")[1]
        }.map {
            it.serverObfTotal!! to it.clientObfTotal!!
        }.toMap().toSortedMap()
    }

    fun generateMethodMappings(methodFile: File): Map<String, String> {
        val methodNames = methodFile.readLines().toMutableList()
        methodNames.removeAt(0) // remove column definition
        val methods = mutableListOf<FieldMapping>()

        methodNames.forEach {
            val parts = it.split(",")
            val deobfName = unquote(parts[1])
            val deobfClass = unquote(parts[5])
            val obfName = unquote(parts[2])
            val sig = unquote(parts[4])
            val obfClass = unquote(parts[6])
            val obf = "$obfClass.$obfName.$sig"
            val isClient = unquote(parts[8]).toInt() == 0

            var foundField = false
            methods.forEach {
                if (it.deobfClass == deobfClass && it.deobfField == deobfName) {
                    foundField = true
                    if (isClient) it.clientObfTotal = obf else it.serverObfTotal = obf
                }
            }

            if (!foundField) {
                if (isClient) {
                    methods.add(FieldMapping(deobfName, deobfClass, obf, null))
                } else {
                    methods.add(FieldMapping(deobfName, deobfClass, null, obf))
                }
            }
        }

        return methods.filter {
            it.clientObfTotal != null && it.serverObfTotal != null
                    && it.clientObfTotal!!.split(".")[1] !=
                    it.serverObfTotal!!.split(".")[1]
        }.map {
            it.serverObfTotal!! to it.clientObfTotal!!
        }.toMap().toSortedMap()
    }

}
