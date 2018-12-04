/**
 * file: com.luochunhui.lib.Remote.groovy
 * 
 * 使用Ansible节点各环境节点，用于上线。
 *
 */
package com.luochunhui.lib
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;

class Remote implements Serializable {
    def script
    def inventory
    def user
    def noticer

    Remote() {}

    Remote(script, inventory, user="ops") {
        this.script = script
        this.inventory = inventory
        this.user = user
        this.noticer = new Noticer(script)
    }

    def DEBUG_PRINT(String msg) {
      script.echo "============ ${msg} ============"
    }

    /**
     * Deploy Entry
     */
    def deployProcess( String playbook, String file, String BUILD_ID="0", ArrayList tags=['update']  ) {
      script.lock(resource: "${playbook}-${inventory}-server", inversePrecedence: true) {
        def currentUser = 'SYSTEM'
        try {
          DEBUG_PRINT "发布开始。项目: ${playbook}, 发布编号: ${BUILD_ID} ; 环境: ${inventory}; 文件: ${file}; Tags: ${tags}； "

          this.unstash (playbook, file, BUILD_ID)

          if(inventory != "test") {
            script.timeout(time:1, unit:'DAYS') {
              def submitter = "";
              if(inventory == 'prod') {
                submitter = "scm,publisher";
              }
              else {
                submitter = "qa,scm,publisher";
              }

              noticer.send( "deploy.ready", "INFO", inventory, playbook, "发布准备妥当。发布编号: ${BUILD_ID}" )

              script.input message: "可以发布 ${inventory} 了吗?", ok: '可以了，发布！', submitter: submitter
            }
          }
          
          noticer.send( "deploy.start", "INFO", inventory, playbook, "发布开始。发布编号: ${BUILD_ID}".toString())

          this.deploy (playbook, file, BUILD_ID, tags)

          //noticer.send( "BUILD_ID: ${BUILD_ID} to ${inventory} deploy success".toString(), "INFO"  )
          noticer.send( "deploy.finished", "INFO", inventory, playbook, "发布完成。发布编号: ${BUILD_ID}".toString() )
          
          script.timeout(time:1, unit:'DAYS') {
            script.input message: "${inventory}测试通过了吗? ", ok: '通过！', submitter: 'qa'
          }

          noticer.send( "deploy.pass", "INFO", inventory, playbook, "测试通过。发布编号: ${BUILD_ID}".toString() )
          
        }
        catch( FlowInterruptedException err ) { //RejectedAccessException
          def level = "INFO"
          if (inventory == "test") {
            level = "INFO"
          }
          else {
            level = "WARNING"
          }
          def message = new StringBuffer().append( err.getResult() )
                      .append( "/" )
                      .append( err.toString() )
                      .toString()
                      
          noticer.send( "testdeploy.rejected", level, inventory, playbook, "发布拒绝。发布编号: ${BUILD_ID} \n ${message}".toString() )
          
          DEBUG_PRINT err.toString()
          throw err
        }
        catch (Throwable err) {
          // def message = new StringBuffer().append( err.getResult() )
          //             .append( "/" )
          //             .append( err.toString() )
          //             .toString()

          // noticer.send( "testdeploy.error", "WARNING", inventory, playbook, "发布失败。发布编号: ${BUILD_ID}\n ${message}".toString() )
          

          // DEBUG_PRINT err.toString()
          throw err
        }
        finally {
          this.clean (playbook, file, BUILD_ID)
        }
      }
    }

    /**
     * deploy core
     */
    def deploy( String playbook, String file, String BUILD_ID="0", ArrayList tags=['update'] ) {
      script.node("ansible-${inventory}") {
        DEBUG_PRINT "${inventory} deploy started"

        def extraString = " -e FILES_PATH=/tmp/${playbook}/${BUILD_ID} -e TARGET_FILE=${file}"

        if (tags.size() > 0) {
          extraString += " --tags ${tags.join(',')}"
        }
        extraString += " -e BUILD_ID=${BUILD_ID}"

        play(playbook, extraString)

        DEBUG_PRINT "${inventory} deployed end"
      }
    }

    /**
     * Run ansible playbook
     */
    def play(String playbook, String extra) {
      def playCmd = "cd ~/rhasta/ && ansible-playbook ${playbook}.yml -i ${inventory} ${extra}"
      script.sh(playCmd)
    }

    /**
     * unstash will copy the file to jenkins slave node
     */
    def unstash(String playbook, String file, String BUILD_ID="0") {
      script.node("ansible-${inventory}") {
        DEBUG_PRINT("unstash[scp] target to server.")

        script.sh "mkdir -p /tmp/${playbook}/${BUILD_ID}/"

        script.dir("/tmp/${playbook}/${BUILD_ID}/") {
          script.deleteDir()
          script.unstash 'targetArchive'
        }
        DEBUG_PRINT("unstash[scp] finished.")
      }
    }


    /**
     * clean files created in deploy process
     */
    def clean(String playbook, String file, String BUILD_ID="0") {
      script.node("ansible-${inventory}") {
        DEBUG_PRINT "${inventory} clean"
        script.sh  "rm -rf /tmp/${playbook}/${BUILD_ID}/"
      }
    }

    def deployAnsible( String file ) {
      deployTarGz(file, "ansible")
    }

    def deployTarGz( String file, String target, String BUILD_ID="0") {
      DEBUG_PRINT "deploy ${file} to ${inventory} with target."

      def filename = file.substring(file.lastIndexOf("/") + 1, file.length());

      def id = UUID.randomUUID().toString()

      script.sh  "mkdir -p /tmp/${target}/${id}/; mkdir -p ~/${target}/"

      script.dir("/tmp/${target}/${id}/") {
        script.deleteDir()
        script.unstash 'targetArchive'
      }

      script.dir("~/${target}/") {
        if(filename.endsWith("gz")) {
          script.sh "tar zxf /tmp/${target}/${id}/${file}"
        }
        else {
          script.sh "cp /tmp/${target}/${id}/${file} ./${file}"
        }
      }

      script.sh  "rm -r /tmp/${target}/${id}/"

      noticer.send( "deploy.finished", "INFO", inventory, target, "发布完成。发布编号: ${BUILD_ID}".toString() )
    }

    def deployDocker(String file, String target, String BUILD_ID="0") {
      script.node("ansible-${inventory}") {
      }
    }

    def deploySetup(String playbook, ArrayList extraParameters=[] ) {
      script.node("ansible-${inventory}") {
        DEBUG_PRINT "${inventory} setup ${playbook} started"

        noticer.send( "setup.ready", "WARNING", inventory, playbook, "系统初始化开始，参数： ${extraParameters}" )

        def extraString = extraParameters.join(" ")

        play(playbook, extraString)

        noticer.send( "setup.finished", "WARNING", inventory, playbook, "系统初始化完成" )

        DEBUG_PRINT "${inventory} deployed end"
      }

      DEBUG_PRINT "setup Check ${playbook} in ${inventory}"
    }


    def rollback(String servers, String servicename, String rollbackTo, String workspace) {
      script.node("ansible-${inventory}") {
        DEBUG_PRINT "${inventory} ${servicename}, rollback to ${rollbackTo} started"

        def playbook = "sos/20-rollback"

        noticer.send( "rollback.ready", "WARNING", inventory, playbook, "回滚开始: ${rollbackTo}" )

        def extraParameters = []
        extraParameters << "-e hosts=${servers}"
        extraParameters << "-e WORKSPACE=${workspace}"
        extraParameters << "-e ROLLBACK_TO=${rollbackTo}"
        extraParameters << "-e SERVICE_NAME=${servicename}"

        def extraString = extraParameters.join(" ")

        play(playbook, extraString)

        noticer.send( "rollback.finished", "WARNING", inventory, playbook, "回滚完成: ${rollbackTo}" )

        DEBUG_PRINT "${inventory} deployed end"
      }
    }
}
