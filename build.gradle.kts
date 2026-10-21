// 根工程不应用插件，构建逻辑在 plugin 子模块
tasks.register("buildAll") {
    dependsOn(":plugin:plugin")
}
