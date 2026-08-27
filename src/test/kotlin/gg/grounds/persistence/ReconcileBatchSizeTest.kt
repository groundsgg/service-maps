package gg.grounds.persistence

import java.lang.reflect.Proxy
import javax.sql.DataSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReconcileBatchSizeTest {

    @Test
    fun `rejects a non-positive reconciliation batch size`() {
        assertThrows<IllegalArgumentException> {
            PostgresMapVersionRepository(unusedDataSource(), 0)
        }
    }

    @Test
    fun `rejects a negative reconciliation batch size`() {
        assertThrows<IllegalArgumentException> {
            PostgresMapVersionRepository(unusedDataSource(), -1)
        }
    }

    private fun unusedDataSource(): DataSource =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, _, _ ->
            error("data source must not be used while validating configuration")
        } as DataSource
}
