/**
 * file: com.luochunhui.lib.Remote.groovy
 * 
 * 使用Ansible节点各环境节点，用于上线。
 *
 */
package com.luochunhui.lib
import static java.nio.charset.StandardCharsets.*;

class Noticer implements Serializable {
    def steps

    Noticer(steps) {
      this.steps = steps
    }

    def send(String event, String level="INFO", String inventory="test", String playbook="DevOps/test", String msg="", String actor="SYSTEM") {
      steps.echo "=======${level}== ${event} = ${ playbook } == ${msg} ============"

      def project = playbook

      steps.node("master") {
        def fullMsg = "== ${project} @ ${inventory} ==\n [${level}]: ${msg}. \n #DevOps #${project} #${inventory}".toString()
        steps.sh "/usr/bin/bearychat -t '${fullMsg}' -c 'DevOps,${project}' -m"
        if (inventory == "prod") {
          def gr = "管理组";
          steps.sh "/usr/bin/bearychat -t '${fullMsg}' -c '${gr}' -m".toString()
          steps.sh "/usr/bin/wechatnotify -t '${fullMsg}' -c '${gr}' -m".toString()
        }
      }
    }
}
