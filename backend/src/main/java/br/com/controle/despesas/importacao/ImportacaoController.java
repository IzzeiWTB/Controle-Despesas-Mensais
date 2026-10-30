package br.com.controle.despesas.importacao;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/importacoes")
@CrossOrigin(origins = "*")
public class ImportacaoController {
    static final long TAMANHO_MAXIMO = 2L * 1024 * 1024;

    private final ImportacaoService service;

    public ImportacaoController(ImportacaoService service) {
        this.service = service;
    }

    /**
     * Le um extrato OFX/CSV. Com confirmar=false apenas pre-visualiza (nada e gravado);
     * com confirmar=true grava os lancamentos novos.
     */
    @PostMapping(value = "/extrato", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> extrato(@RequestParam("arquivo") MultipartFile arquivo,
                                     @RequestParam(defaultValue = "") String conta,
                                     @RequestParam(defaultValue = "false") boolean cartao,
                                     @RequestParam(defaultValue = "false") boolean confirmar,
                                     @RequestParam(defaultValue = "false") boolean incluirPossiveisDuplicados,
                                     @RequestParam(defaultValue = "false") boolean incluirPagamentosFatura) throws IOException {
        if (arquivo.isEmpty()) return erro(HttpStatus.UNPROCESSABLE_ENTITY, "Escolha um arquivo OFX ou CSV.");
        if (arquivo.getSize() > TAMANHO_MAXIMO) return erro(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo maior que 2 MB. Exporte um período menor.");
        if (conta.length() > 60) return erro(HttpStatus.UNPROCESSABLE_ENTITY, "O nome da conta deve ter até 60 caracteres.");
        try {
            return ResponseEntity.ok(service.processar(arquivo.getBytes(), arquivo.getOriginalFilename(),
                    new ImportacaoService.Opcoes(conta, cartao, confirmar, incluirPossiveisDuplicados, incluirPagamentosFatura)));
        } catch (ExtratoInvalidoException e) {
            return erro(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Extrato importado por outra requisição", e);
        }
    }

    private static ResponseEntity<Map<String, String>> erro(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).body(Map.of("erro", mensagem));
    }

    /** Upload acima do limite do servidor (spring.servlet.multipart) antes de chegar ao controller. */
    @RestControllerAdvice
    static class LimiteUpload {
        @ExceptionHandler(MaxUploadSizeExceededException.class)
        ResponseEntity<Map<String, String>> muitoGrande() {
            return erro(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo maior que 2 MB. Exporte um período menor.");
        }
    }
}
