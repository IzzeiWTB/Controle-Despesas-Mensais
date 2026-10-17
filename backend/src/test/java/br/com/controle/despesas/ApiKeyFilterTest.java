package br.com.controle.despesas;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ApiKeyFilterTest {
    @Test void unconfiguredKeyMustNeverAllowFinancialData() throws Exception {
        var filter = new ApiKeyFilter();
        ReflectionTestUtils.setField(filter, "configuredApiKey", "");
        var request = new MockHttpServletRequest("GET", "/api/movimentacoes");
        request.addHeader("Origin", "https://localhost");
        var response = new MockHttpServletResponse();
        var reachedController = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> reachedController.set(true));
        assertEquals(503, response.getStatus());
        assertEquals("*", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertFalse(reachedController.get());
    }
}
