package com.luochunhui.lib


class Deployer implements Serializable {
  def steps

  def toTest(playbook, targetFile) {
    node {
      remote = new Remote(steps, 'test')
      remote.deploy (playbook, targetFile, BUILD_ID, 'update')
    }
  }
}
