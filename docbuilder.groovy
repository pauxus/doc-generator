/*
 * Creates a file structure for a single system.
 *
 * Usage: CreateSystems.groovy --help
 */


import groovy.text.GStringTemplateEngine

import static groovy.io.FileType.*

content = [:]
options = parseOptions(args)
targetBaseDir = options.output as File
engine = new GStringTemplateEngine()
systems = filterUsingClosure(readSystemsFrom(resolveXMLSourceDirectory()))
ant = new AntBuilder()
ant.project.buildListeners[0].messageOutputLevel = 0

println "Handling Systems: ${systems.keySet()}"

deleteTargetDirectory()
generateFilesFromStructureTemplates()
generateFilesFromOverviewTemplates()
copyStaticResources()

println "All Done..."

//--------------------------------- Methods -----------------------------------

def deleteTargetDirectory() {
    if (!options.noClean) {
        println "Deleting target directory $targetBaseDir"
        ant.delete(dir: targetBaseDir)
    }
}

def generateFilesFromStructureTemplates() {
    if (!content.structure) { return }
    def structure = content.structure as File
    println ""
    println "Handling structure: $structure"

    if (structure.isDirectory()) {
        systems.each{ name, values ->
            def targetDir = new File(targetBaseDir, name)
            targetDir.mkdirs()

            println "--> $name"

            structure.eachFileRecurse(FILES) { template ->
                def templateRelativeFolderName = template.parentFile.absolutePath - structure.absolutePath
                println "----> $templateRelativeFolderName${File.pathSeparator}$template.name"
                def folder = new File(targetDir, templateRelativeFolderName)
                folder.mkdirs()
                new File(folder, template.name).setText(engine.createTemplate(template.newReader('UTF-8')).make(system: values, systems: systems, systemName: name).toString(), 'UTF-8')
            }
        }
    } else {
        systems.each{ name, values ->
            println "--> $name"
            def targetDir = new File(targetBaseDir, name)
            targetDir.mkdirs()
            println "----> $structure.name"
            new File(targetDir, structure.name).setText(engine.createTemplate(structure.newReader('UTF-8')).make(system: values, systems: systems, systemName: name).toString(), 'UTF-8')
        }
    }
}

def generateFilesFromOverviewTemplates() {
    if (!content.overview) { return }

    println ""
    def overviewDir = content.overview as File
    println "Handling overview : $overviewDir"
    targetBaseDir.mkdirs()

    if (overviewDir.isFile()) {
        new File(targetBaseDir, overviewDir.name).setText(
                engine.createTemplate(overviewDir.newReader('UTF-8')).make(systems: systems).toString(), 'UTF-8')
    } else {
        overviewDir.eachFileRecurse(FILES) { template ->

            def templateRelativeFolderName = template.parentFile.absolutePath - overviewDir.absolutePath

            def folder = new File(targetBaseDir, templateRelativeFolderName)
            folder.mkdirs()
            new File(folder, template.name).setText(
                    engine.createTemplate(template.newReader('UTF-8')).make(systems: systems).toString(), 'UTF-8')
        }
    }
}

def copyStaticResources() {
    if (!content.static) { return }

    println ""
    println "Copying static content from ${content.static}"
    ant.copy(todir: new File(targetBaseDir, (content.static as File).name)) {
        fileset(dir: content.static)
    }
}

/**
 * Creates structure containing the contents of all environment files.
 *
 * For each file with the name pattern <type>-<system>.<ext>, first checks whether it is included or not.
 *
 * Next the file is parsed as xml or properties file and entered into a two step map:
 * "systemname" -> "type" -> contents
 *
 * @param xmlDir
 * @return "systemname" -> "type" -> contents map
 */
def readSystemsFrom(File xmlDir) {
    def result = [:]
    getNamesOfAllSystemsInDirectoryWithBasicFiltersApplied(xmlDir).each { it ->
        result[it] = [:]
    }

    if (!result) {
        println "No systems to process, aborting"
        System.exit(1)
    }

    def slurper = new XmlSlurper()
    def folders = [xmlDir]

    if (options.mixinss) {
        folders += options.mixinss.collect{ new File(it) }
    }
    folders.each { basedir ->
        basedir.eachFile { file ->
            def (total, type, name, extension) = (file.name =~ ~/(.*)-(.*)\.(.*)/)[0]
            name = name.toLowerCase()
            if (!result.containsKey(name)) {
                return
            }

            if (result[name][type] != null) {
                System.err.println("duplicate files: $type-${name.toUpperCase()}")
                System.exit(1)
            }

            if (extension == "xml") {
                result[name][type] = slurper.parse(file)
            } else if (extension == "properties") {
                def props = new Properties()
                props.load(file.newInputStream())
                result[name][type] = props
            } else {
                System.err.println("WARNING: Filetype $extension is not supported")
            }
        }
    }

    return result
}
/**
 * Filters the system Map according to the closue given as argument (if present).
 *
 * @return a map containing only the elements matching the filter.
 */
def filterUsingClosure(def systems) {

    if (!options.filter)
        return systems


    def filterClosure = options.filter

    println "applying filter ${filterClosure}"

    // the "a = " forces to the groovy shell to interpret the string
    // as a closure and not as a literal array
    def filter = new GroovyShell().evaluate("a = $filterClosure")

    def result
    if (filter.maximumNumberOfParameters == 1) {
        result = systems.findAll{ key, value -> filter.call(value)}
    } else {
        result = systems.findAll(filter)
    }

    if (!result) {
        println "Filter removed all systems!"
        System.exit(1)
    }

    if (result.size() == systems.size()) {
        println "WARNING: Filter did not remove anything."
    }

    println "Filter removed ${systems.size() - result.size()} of ${systems.size()} entries."

    return result
}

def getNamesOfAllSystemsInDirectoryWithBasicFiltersApplied(File xmlDir) {
    def names = xmlDir.list().grep( ~/environment-.*\.xml/ ).collect {
        (it - "environment-" - ".xml").toLowerCase()
    }
    if (options.includes) {
        findInvalidIncludesExcludes(names, options.includes, "includes")
        names = names.intersect(options.includes)
    }

    if (options.excludes) {
        findInvalidIncludesExcludes(names, options.excludes, "excludes")
        names.removeAll(options.excludes)
    }

    return names
}

def findInvalidIncludesExcludes(def names, def includeExclude, String message) {
    def invalid = includeExclude.clone()
    invalid.removeAll(names)
    if (invalid) {
        println "WARNING: The following $message are not part of the source directories:"
        println invalid
    }
}

def getDownloadURLFromGAVOptions() {
    def (groupId, artifactId, version, type, classifier) = options.gav.split(":")

    if (!version) {
        def versions = new XmlSlurper().parse("$options.repository/${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml")
        version = versions.versioning.versions.version[0].text()
    }

    def trueVersion = version
    if (version.endsWith("-SNAPSHOT")) {
        def metaInfo = new XmlSlurper().parse("$options.repository/${groupId.replace('.', '/')}/$artifactId/$version/maven-metadata.xml")
        def (timestamp, buildnumber) = metaInfo.versioning.snapshot."*"*.text()

        trueVersion = "${version - '-SNAPSHOT'}-$timestamp-$buildnumber"
    }

    return "$options.repository/${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$trueVersion-${classifier}.$type"
}

File resolveXMLSourceDirectory() {
    if (options.repository) {
        def temp = File.createTempDir();

        def download = new File(temp, 'download.jar')
        def url = getDownloadURLFromGAVOptions()
        println "Downloading environment data from '$url'"

        ant.get(src: url, dest: download)
        ant.unzip(src: download, dest: temp)
        ant.delete(file: download)

        if (options.source) {
            return new File(temp, options.source)
        } else {
            return temp
        }
    } else {
        return options.source as File
    }
}

def parseOptions(args) {

    def cli = new CliBuilder(
            usage: "${getClass().name.split("\\.")[-1]}.groovy [-options]",
            header: "Generates system information out of environment files. Call with --help to get additional information"
    )
    cli.with{
        s(longOpt: 'source', args:1, required: false, 'directory containing system xmls. if repository/gav is given, this is relative to the artifact')
        r(longOpt: 'repository', args:1, required: false, 'URL of repository to download from')
        c(longOpt: 'gav', args:1, required: false, 'coordinates to download (groupId:artifactId:version:type:classifier)')
        o(longOpt: 'output', args:1, type: File, required: true, argName:"dir", 'Directory to write to. Will be created if missing')
        _(longOpt: 'structure', args:1, type: File, required: false, argName:"dir", 'Directory containing the structure templates')
        _(longOpt: 'overview', args:1, type: File, required: false, argName:"dir", 'Directory containing the overview templates')
        _(longOpt: 'static', args:1, type: File, required: false, argName:"dir", 'Directory containing static resources')
        _(longOpt: 'complete', args:1, type: File, required: false, argName:"dir", 'Directory containing all resources')
        i(longOpt: 'include', args: -2, valueSeparator: ',', 'Comma separated list of systems to include')
        e(longOpt: 'exclude', args: -2, valueSeparator: ',', 'Comma separated list of systems to exclude')
        _(longOpt: 'mixins', args: -2, valueSeparator: ',', type: File, 'Comma separated list of folders containing additional system files to include')
        n(longOpt: 'no-clean', 'Normally, the target folder will be clean, this options prevents this.')
        _(longOpt: 'filter', args: 1, 'Closure to filter matching systems')
        h(longOpt: 'help', 'Show this help')
    }


    if (args.find { it ==~ /-h|--help/ }) {
        cli.usage()
        println ""
        println ('''Concepts:
---------
.Sources
 Source can either be a folder in a the local filesystem or an artifact
downloaded from a remote repository.
If a remote repository is used, both --repository and --gav arguments must be
set.

.Additional Sources
Additional directories can be included in the base data using the --mixin
argument. Files in these directories must conform to the format
<type>-<SYSTEM>.[xml|properties]. These files will be included in the data
structure an can be reached using $data.<system>.<type> for overview templates
or data.<type> for structure templates. XML files will be included as GPath
(XML-Slurper based), property files as simple maps.

.Structures
 A Structure is a folder or file containing the templates for a single system.
In the target folder, a separate directory for each system is generated,
containing the applied templates for this specific system.
Each templates gets the following bindings:
system     : the data of all source files of this particular system
systems    : the data for all systems
systemName : the name of the current system

.Overview
 An overview structure is a folder containing data for all systems. It is
generated directly into the target directory. Binding data contains only
'systems'.

.Filters
 Filters can be used to include and exclude systems. There are two types
of filters:
--include and --exclude contain comma separated list of system, only one of
those can be specified.
--filter contains a groovy closure to be applied to a elements of the system
map. This closure can have either one (including the default 'it' or two
arguments:
One Argument: Contains the data block of specific system
Examples:
  { system -> system.environment.version == "V5" },
  { it.environment.version == "V5" }
Two arguments: first argument is name, second system data.
Examples:
  { name, data -> name ==~ ~/dev.*/ && data.db2.version = "10.1.3" }

''')
        System.exit(1)
    }
    def options = cli.parse(args)

    if (!options) {
        System.exit(1)
    }

    if (!options.source && !options.repository) {
        printUsageAndExit(cli, "Must define either source or repo (and possibly source)?")
    }

    if ((options.repository as boolean) ^ (options.gav as boolean)) {
        printUsageAndExit cli, "must define both repository and gav"
    }

    if (options.include && options.exclude) {
        printUsageAndExit cli, "can only define one of 'include' and 'exclude'"
    }

    if (!options.structure && !options.overview && !options.complete) {
        printUsageAndExit cli, "must define at least one of 'structure', 'overview' and 'complete'"
    }

    if (options.complete && (options.overview || options.structure || options.static)) {
        printUsageAndExit cli, "must define either 'complete' or one or more of 'structure', 'overview' and 'static'"
    }
    if (options.filter && !(options.filter ==~ ~/^\{(?:\s*\w+\s*(?:\s*,\s*\w+\s*)?->)?.*\}$/)) {
        println options.filter
        printUsageAndExit cli, "filter must be a closure '{ it -> ...}'"
    }

    if (options.complete) {
        content.static = options.complete + "/static"
        content.overview = options.complete + "/overview"
        content.structure = options.complete + "/structure"
    } else {
        content.static = options.static
        content.overview = options.overview
        content.structure = options.structure
    }

    return options
}

def printUsageAndExit(def cli, String message) {
    println ""
    println "ERROR: $message"
    println ""

    cli.usage()
    System.exit(1)
}

