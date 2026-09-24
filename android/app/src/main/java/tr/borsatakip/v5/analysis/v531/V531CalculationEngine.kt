package tr.borsatakip.v5.analysis.v531

import tr.borsatakip.v5.analysis.v533.V533TemporalCausality
import tr.borsatakip.v5.analysis.v533.V533TemporalEvidence

object V531CalculationEngine {
    fun calculate(input: V531CalculationInput): V531SignalResult {
        require(input.mtfConsensusScore == null && input.mtfAsOfTimestamp == null) {
            "MTF_CONTEXT_MUST_BE_APPLIED_ONLY_BY_V531ContextEngine"
        }
        val evaluationTimestamp = input.evaluationTimestamp
        if (evaluationTimestamp != null) {
            val extraViolations = buildList {
                if (input.technicalAsOfTimestamp == null) add("TECHNICAL_ASOF_MISSING")
                if (input.openInterestChangePct != null && input.openInterestAsOfTimestamp == null) add("OI_ASOF_MISSING")
            }
            val temporal = V533TemporalCausality.evaluate(
                input.candles,
                V533TemporalEvidence(
                    evaluationTimestamp = evaluationTimestamp,
                    technicalAsOfTimestamp = input.technicalAsOfTimestamp ?: -1L,
                    openInterestAsOfTimestamp = input.openInterestAsOfTimestamp,
                    mtfAsOfTimestamp = null
                )
            )
            val violations = (temporal.violations + extraViolations).distinct()
            if (violations.isNotEmpty()) {
                val confidence = ConfidenceEngine.evaluate(input.dataQuality.copy(candlesValid = false))
                return V531SignalResult(
                    ScoringConfig.ENGINE_VERSION, 0, 0, 0, 0, V531Direction.WATCH, V531Direction.WATCH,
                    confidence.score, confidence.band, 100, V531RiskLevel.UNKNOWN, 0, 0, 0, emptyList(),
                    (confidence.reasonCodes + V531ReasonCode.TEMPORAL_CAUSALITY_VIOLATION).distinct(),
                    listOf("ENGINE=${ScoringConfig.ENGINE_VERSION}", "TEMPORAL_CAUSALITY_VIOLATION=${violations.joinToString(",")}")
                )
            }
        }
        if (!input.currentPrice.isFinite() || input.currentPrice <= 0.0) {
            val confidence = ConfidenceEngine.evaluate(input.dataQuality.copy(quoteValid = false))
            return V531SignalResult(
                ScoringConfig.ENGINE_VERSION, 0, 0, 0, 0, V531Direction.WATCH, V531Direction.WATCH,
                confidence.score, confidence.band, 100, V531RiskLevel.UNKNOWN, 0, 0, 0, emptyList(),
                (confidence.reasonCodes + V531ReasonCode.INVALID_PRICE).distinct(),
                listOf("ENGINE=${ScoringConfig.ENGINE_VERSION}", "INVALID_PRICE")
            )
        }

        val priceChangePct = input.candles.takeIf { it.size >= 2 }?.let {
            ScoreNormalizer.pct(it[it.lastIndex - 1].close, it.last().close)
        }
        val components = listOf(
            TrendEngine.evaluate(input.currentPrice, input.technical),
            MomentumEngine.evaluate(input.candles, input.technical),
            StructureEngine.evaluate(input.candles),
            VolumeEngine.evaluate(input.candles, input.technical),
            OpenInterestEngine.evaluate(priceChangePct, input.openInterestChangePct, input.technical.volumeRatio),
            VolatilityEngine.evaluate(input.candles, input.technical, input.currentPrice)
        )
        val availableWeight = components.filter { it.available }.sumOf { it.weight }.coerceIn(0, ScoringConfig.TOTAL_SIGNAL_WEIGHT)
        val longScore = components.sumOf { it.longContribution }.coerceIn(0, 100)
        val shortScore = components.sumOf { it.shortContribution }.coerceIn(0, 100)
        val confidence = ConfidenceEngine.evaluate(input.dataQuality)

        // MTF is intentionally excluded from the base engine. It is applied exactly once
        // by V531ContextEngine after the base signal has been calculated.
        val mtfConflict: Int? = null
        val risk = RiskEngine.evaluate(
            RiskEngine.Input(
                price = input.currentPrice,
                atr = input.technical.atr14,
                spreadPct = input.spreadPct,
                liquidityScore = input.liquidityScore,
                dataConfidence = confidence.score,
                mtfConflictSeverity = mtfConflict,
                structureDistanceAtr = input.structureDistanceAtr,
                slippageSensitivity = input.slippageSensitivity,
                contractRiskPct = input.contractRiskPct
            )
        )
        val decision = SignalDecisionEngine.evaluate(longScore, shortScore, confidence.score, availableWeight, null)
        val reasons = (confidence.reasonCodes + risk.reasonCodes + decision.reasonCodes).distinct()
        val audit = buildList {
            add("ENGINE=${ScoringConfig.ENGINE_VERSION}")
            add("AVAILABLE_SIGNAL_WEIGHT=$availableWeight/${ScoringConfig.TOTAL_SIGNAL_WEIGHT}")
            components.forEach { c ->
                val strength = c.signedStrength?.let { "%.4f".format(java.util.Locale.US, it) } ?: "N/A"
                add("COMPONENT=${c.id};WEIGHT=${c.weight};STRENGTH=$strength;LONG=${c.longContribution};SHORT=${c.shortContribution}")
            }
            add("RAW_LONG=$longScore")
            add("RAW_SHORT=$shortScore")
            add("CONFLICT_PENALTY=${decision.conflictPenalty}")
            add("FINAL_SCORE=${decision.finalScore}")
            add("CONFIDENCE=${confidence.score};BAND=${confidence.band.name}")
            add("RISK=${risk.score};LEVEL=${risk.level.name};COVERAGE=${risk.coveragePercent}")
            add("DECISION=${decision.decision.name}")
            add("REASONS=${reasons.joinToString(",") { it.name }}")
        }
        return V531SignalResult(
            engineVersion = ScoringConfig.ENGINE_VERSION,
            longScore = decision.longScore,
            shortScore = decision.shortScore,
            finalSignalScore = decision.finalScore,
            rawSignalScore = decision.rawScore,
            dominantDirection = decision.dominantDirection,
            decision = decision.decision,
            dataConfidence = confidence.score,
            confidenceBand = confidence.band,
            riskScore = risk.score,
            riskLevel = risk.level,
            riskCoveragePercent = risk.coveragePercent,
            availableSignalWeight = availableWeight,
            conflictPenalty = decision.conflictPenalty,
            components = components,
            reasonCodes = reasons,
            auditLines = audit
        )
    }
}
