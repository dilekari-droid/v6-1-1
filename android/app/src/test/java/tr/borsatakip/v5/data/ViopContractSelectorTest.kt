package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopContractType

class ViopContractSelectorTest {
    private fun contract(symbol: String, type: ViopContractType) = ViopContract(
        symbol = symbol,
        underlying = "XU030",
        expiry = "2026-12",
        contractType = type,
        providerId = "backend",
        providerLabel = "backend",
        dataTimestamp = 1_700_000_000_000L,
        receivedAt = 1_700_000_000_100L,
        dataMode = DataMode.REALTIME,
        validity = SignalValidity.VALID,
        validityReason = "ok",
        lastTradingAt = 2_000_000_000_000L,
        expiryAt = 2_000_000_000_000L
    )

    @Test
    fun optionCanNeverBeSelectedAsFuture() {
        val option = contract("O_XU030_C_10000", ViopContractType.OPTION)
        assertEquals(emptyList<ViopContract>(), ViopContractSelector.candidates(listOf(option), "XU030", 1_800_000_000_000L))
        assertNull(ViopContractSelector.resolve("XU030", listOf(option), 1_800_000_000_000L))
    }

    @Test
    fun futureCanBeSelected() {
        val future = contract("F_XU0301226", ViopContractType.FUTURE)
        assertEquals(future, ViopContractSelector.resolve("XU030", listOf(future), 1_800_000_000_000L))
    }
}
