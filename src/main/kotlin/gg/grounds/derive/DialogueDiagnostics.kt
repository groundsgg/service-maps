package gg.grounds.derive

import gg.grounds.dialogue.core.validation.ValidationReport
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem

object DialogueDiagnostics {
    fun fromLibrary(problem: ValidationReport.Problem, definitionPaths: Set<String> = emptySet()): DeriveProblem {
        val original = problem.file()
        val file = if (original != null && original in definitionPaths) "dialogues/$original" else original
        return DeriveProblem(DeriveFailureScope.CONTENT, file, problem.code(), problem.nodeId(), problem.message(),
            "dialogue", problem.severity().name, file, problem.path(), problem.nodeId())
    }

    /** Bound the serialized UTF-8 array, not its character count; truncation cannot hide failure. */
    fun bounded(problems: List<DeriveProblem>): List<DeriveProblem> {
        val sorted = problems.sortedWith(compareBy({ it.file ?: it.path ?: "" }, { it.pointer ?: "" }, { it.code }, { it.nodeId ?: "" }))
        val error = problems.any { it.severity == null || it.severity == "ERROR" }
        val marker = DeriveProblem(if (problems.any { it.scope == DeriveFailureScope.SYSTEM }) DeriveFailureScope.SYSTEM else DeriveFailureScope.CONTENT,
            null, "DIAGNOSTICS_TRUNCATED", null, "Diagnostic report truncated", "dialogue", if (error) "ERROR" else "WARNING")
        val markerBytes = CanonicalJson.write(marker).size - 1
        val kept = mutableListOf<DeriveProblem>()
        val sizes = mutableListOf<Int>()
        var bytes = 3 // brackets plus the CanonicalJson terminal newline
        for (problem in sorted) {
            val size = CanonicalJson.write(problem).size - 1
            val next = bytes.toLong() + size + if (kept.isEmpty()) 0 else 1
            if (kept.size == 1000 || next > 1048576) break
            kept += problem
            sizes += size
            bytes = next.toInt()
        }
        if (kept.size == sorted.size) return java.util.List.copyOf(kept)
        while (kept.isNotEmpty() && (kept.size >= 1000 || bytes.toLong() + markerBytes + 1 > 1048576)) {
            val size = sizes.removeAt(sizes.lastIndex)
            kept.removeAt(kept.lastIndex)
            bytes -= size + if (kept.isEmpty()) 0 else 1
        }
        kept += marker
        return java.util.List.copyOf(kept)
    }
}
