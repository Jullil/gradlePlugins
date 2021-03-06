package com.github.jullil.IntelliJIdeaProjectToGradle;

import org.gradle.api.*
import org.gradle.util.*


class IntelliJIdeaProjectToGradlePlugin implements Plugin<Project> {

    static def parseIdeaLibraries(List<File> libraries, Project project) {
        def result = [:]
        def projectPath = project.projectDir.absolutePath
        libraries.each {
            def rootTag = new XmlParser().parse(it)

            result += [(rootTag.library.@name.get(0)):
                               [
                                       'classesPath': getFilePath(rootTag.library.CLASSES.root.@url, projectPath),
                                       'javaDocPath': getFilePath(rootTag.library.JAVADOC.root.@url, projectPath),
                                       'sourcesPath': getFilePath(rootTag.library.SOURCES.root.@url, projectPath)
                               ]
            ]
        }
        result
    }

    static def parseIdeaProjectModules(File modulesFile, Project project) {
        def projectPath = getProjectPath(project)
        def userHomePath = getUserHomePath()
        def root = new XmlParser().parse(modulesFile)
        Map<String, Object> modules = [:]
        root.find{ it.@name == 'ProjectModuleManager' }.collect{ getFilePath(it.module.@filepath, projectPath) }.first().each{ moduleImlPath ->
            File moduleImlFile = new File((String) moduleImlPath)
            def moduleRootDir = moduleImlPath.replaceAll(/^($projectPath\/)?(.*)[\\\/]+[^\\\/]+\.iml$/){all, pPath, path -> path }
            def moduleName = moduleImlFile.name.replace('.iml', '')
            def moduleGradleName = moduleRootDir.replaceAll(/^($userHomePath)?[\\\/\.]*\.*(.*)/){all, var, path -> path }.replace('/', ":")
            modules += [(moduleName) : [
                    'name'       : moduleName,
                    'gradleName' : moduleGradleName,
                    'imlFile'    : moduleImlFile,
                    'rootDir'    : moduleName == moduleRootDir ? '' : moduleRootDir
            ]]
        }
        modules
    }

    static def getFilePath(List<Path> path, String projectPath) {
        projectPath = projectPath.replace('\\', '/')
        path.collect {
            it.replaceAll(/^jar:\\/\\//, '').replaceAll(/!\\/$/, '').replace('\$PROJECT_DIR\$', projectPath).replace('\$USER_HOME\$', getUserHomePath())
        }
    }

    static def getProjectPath(Project project) {
      project.projectDir.absolutePath.replace('\\', '/')
    }
    static def getUserHomePath() {
      System.properties['user.home'].replace('\\', '/')
    }

    static def cleanSourcePath(String url) {
        url.replaceAll(/^file:\/\/(.)*/) {all, path -> path}.replace('\$MODULE_DIR\$', '')
    }

    static def generateBuildScript(Map moduleParams, Map projectModules, Map libraries, Project project) {
        File file = (File) moduleParams['imlFile']
        File buildFile = new File(file.parent + "/build.gradle")
        def root = new XmlParser().parse(file)

        def sourceSetsOutput = generateSourceSets(root.component.content.sourceFolder)
        if (!sourceSetsOutput.empty) {
            buildFile << sourceSetsOutput + "\n\n"
        }
        def dependencies = generateDependencies(root.component.orderEntry, projectModules, libraries, project)
        if (!dependencies.empty) {
            buildFile << dependencies + "\n\n"
        }
    }

    def static generateSourceSets(List<Path> sourceList) {
        def sources = [
                'main': [
                        'java'     : [],
                        'resources': []
                ],
                'test': [
                        'java'     : [],
                        'resources': []
                ]
        ]
        sourceList.each {
            def path = cleanSourcePath(it.@url)
            if (it.@isTestSource == 'false') {
                sources['main']['java'] += "'${path}'"
            } else if (it.@isTestSource == 'true') {
                sources['test']['java'] += "'${path}'"
            } else if (it.@type == 'java-resource') {
                sources['main']['resources'] += "'${path}'"
            } else if (it.@type == 'java-test-resource') {
                sources['test']['resources'] += "'${path}'"
            }
        }

        def javaSourcesOutput = ""
        if (!sources['main']['java'].empty) {
            javaSourcesOutput = """java {
                                    |           srcDirs = ${sources['main']['java']}
                                    |       }""".stripMargin()
        }
        def testSourcesOutput = ""
        if (!sources['test']['java'].empty) {
            testSourcesOutput = """java {
                                    |           srcDirs = ${sources['test']['java']}
                                    |       }""".stripMargin()
        }
        def resourcesOutput = ""
        if (!sources['main']['resources'].empty) {
            resourcesOutput = """resources {
                                    |           srcDirs = ${sources['main']['resources']}
                                    |       }""".stripMargin()
        }
        def testResourcesOutput = ""
        if (!sources['test']['resources'].empty) {
            testResourcesOutput = """resources {
                                    |           srcDirs = ${sources['test']['resources']}
                                    |       }""".stripMargin()
        }
        def mainSourceSetOutput = ""
        if (!javaSourcesOutput.empty || !resourcesOutput.empty) {
            mainSourceSetOutput = """main {
                                    |       $javaSourcesOutput
                                    |       $resourcesOutput
                                    |   }""".stripMargin()
        }
        def testSourceSetOutput = ""
        if (!testSourcesOutput.empty || !testResourcesOutput.empty) {
            testSourceSetOutput = """test {
                                    |       $testSourcesOutput
                                    |       $testResourcesOutput
                                    |   }""".stripMargin()
        }
        if (!mainSourceSetOutput.empty || !testSourceSetOutput.empty) {
            """sourceSets {
                |   $mainSourceSetOutput
                |   $testSourceSetOutput
                |}""".stripMargin()
        } else {
            ""
        }
    }

    def static generateDependencies(List<Path> projectDependencies, Map allProjects, Map allLibraries, Project project) {
        def libs = projectDependencies.findAll { it.@type == "library" && it.@level == "project" }
        def projects = projectDependencies.findAll { it.@type == "module" }

        def libraryAliases = project.IdeaToGradleConverter.libraryAliases

        def libFilePaths = []
        def libArtifactories = []
        libs.each {
            def libName = it.@name
            if (libraryAliases.containsKey(libName)){
                def libraryAlias = libraryAliases.get(libName)
                if (libraryAlias instanceof List) {
                    libraryAlias.each{
                        libArtifactories += "'$it'"
                    }
                } else {
                    libArtifactories += "'$libraryAlias'"
                }
            } else if (allLibraries.containsKey(libName)) {
                def libPath = allLibraries.get(libName)
                libPath['classesPath'].each {
                    libFilePaths += "'$it'"
                }
            }
        }
        def libsOutput = ""
        if (!libFilePaths.empty) {
            libsOutput += "    compile files($libFilePaths)\n"
        }
        if (!libArtifactories.empty) {
            libsOutput += "    compile ($libArtifactories)"
        }

        def projectsOutput = ""
        projects.each {
            def name = it.@'module-name'
            if (allProjects.containsKey(name)) {
                def projectProps = allProjects.get(name)
                projectsOutput += "    compile project(':${projectProps['name']}')\n"
            }
        }

        if (!libsOutput.empty || !projectsOutput.empty) {
            """dependencies {
                |$libsOutput
                |$projectsOutput
            |}""".stripMargin()
        } else {
            ""
        }
    }

    static def addNewProjectToGradle(Map moduleParams, File gradleSettingsFile) {
        gradleSettingsFile << "include '${moduleParams['name']}'\n"
        if (!moduleParams['rootDir'].empty) {
            gradleSettingsFile << "project(':${moduleParams['name']}').projectDir = file('${moduleParams['rootDir']}')\n"
        }
    }

    @Override
    void apply(Project project) {
        project.extensions.create("IdeaToGradleConverter", IntelliJIdeaProjectToGradleExtension)

        project.task('convertModule') << {

            def ideaProjectLibraries = parseIdeaLibraries(project.fileTree(dir: '.idea/libraries', include: '**/*.xml').findAll(), project)
            File gradleSettingsFile = project.file('settings.gradle')

            Map<String, Project> gradleSubProjects = [:]
            project.allprojects.each{ gradleSubProjects += [(it.name): it]}

            File ideaModuleFile = project.file('.idea/modules.xml')
            if (ideaModuleFile.exists()) {
                def projectModules = parseIdeaProjectModules(ideaModuleFile, project)
//                println projectModules
//                println ideaProjectLibraries
                projectModules.each{ module ->
                    def moduleParams = module.value
                    String moduleName = moduleParams['name']
                    if (!moduleName.empty) {
                        if (!gradleSubProjects.containsKey(moduleName)) {
                            def moduleDir = ((File) moduleParams['imlFile']).parentFile
                            if (moduleDir.listFiles().findAll { it.name.endsWith('build.gradle') }.empty) {
                                generateBuildScript(moduleParams, projectModules, ideaProjectLibraries, project)
                            }
                            addNewProjectToGradle(moduleParams, gradleSettingsFile)
                        }
                    }
                }
            }
        }
    }
}

class IntelliJIdeaProjectToGradleExtension {
    def Map libraryAliases = [:]
}