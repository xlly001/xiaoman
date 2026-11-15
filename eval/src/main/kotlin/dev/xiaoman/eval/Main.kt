package dev.xiaoman.eval

import java.io.File

/**
 * CI entry point: `./gradlew :eval:run`. Prints the report, writes build/reports/eval/{report.txt,report.json},
 * and exits with code 1 when any scenario fails so a safety regression cannot go unnoticed.
 */
fun main(args: Array<String>) {
    val report = EvalRunner.runAll(builtInScenarios())
    val text = report.render()
    println(text)
    if ("--json" in args) println(report.toJson())
    runCatching {
        val dir = File("build/reports/eval")
        dir.mkdirs()
        File(dir, "report.txt").writeText(text)
        File(dir, "report.json").writeText(report.toJson())
    }.onFailure { System.err.println("评测报告写入失败：${it.message}") }
    if (!report.allPassed) kotlin.system.exitProcess(1)
}
