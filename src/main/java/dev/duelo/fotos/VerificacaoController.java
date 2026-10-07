package dev.duelo.fotos;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirma que o Gemma local responde. Serve para diagnosticar o ambiente
 * antes de enviar fotos.
 */
@RestController
class VerificacaoController {

	private static final Logger log = LoggerFactory.getLogger(VerificacaoController.class);

	private final ChatClient chat;

	VerificacaoController(ChatClient.Builder builder) {
		this.chat = builder.build();
	}

	@GetMapping("/verificar")
	ResponseEntity<Map<String, Object>> verificar() {
		long inicio = System.nanoTime();
		try {
			String resposta = chat.prompt()
				.user("Responda em português, com uma única frase curta: o que é um passeio?")
				.call()
				.content();
			if (resposta == null || resposta.isBlank()) {
				log.warn("O modelo devolveu uma resposta vazia");
				return indisponivel();
			}
			long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
			return ResponseEntity.ok(Map.of("ok", true, "ms", ms, "resposta", resposta.strip()));
		}
		catch (RuntimeException e) {
			// A mensagem da exceção pode citar o que foi enviado ao modelo; só a classe vai para o log.
			log.warn("Falha ao chamar o modelo: {}", e.getClass().getSimpleName());
			return indisponivel();
		}
	}

	private static ResponseEntity<Map<String, Object>> indisponivel() {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(Map.of("ok", false, "erro", "O modelo local não respondeu. O Ollama está aberto?"));
	}

}
