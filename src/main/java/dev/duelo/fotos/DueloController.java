package dev.duelo.fotos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

import dev.duelo.fotos.Duelo.Jogador;
import dev.duelo.fotos.Duelo.Rodada;
import dev.duelo.fotos.Juiz.Veredito;

/**
 * O duelo de fotos em três telas curtas: sortear os temas antes de sair, entregar a
 * carta secreta de cada jogador e, na volta, receber as fotos e mostrar o placar.
 */
@Controller
class DueloController {

	private static final Logger log = LoggerFactory.getLogger(DueloController.class);

	private static final int MAX_NOME = 30;

	private static final int MAX_LUGAR = 60;

	private static final String SEM_MODELO = "O modelo local não respondeu. O Ollama está aberto?";

	private static final DateTimeFormatter FORMATO_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final Juiz juiz;

	private final DueloRepositorio duelos;

	DueloController(Juiz juiz, DueloRepositorio duelos) {
		this.juiz = juiz;
		this.duelos = duelos;
	}

	@GetMapping("/")
	String inicio() {
		return "index";
	}

	@PostMapping("/duelos")
	ModelAndView criar(@RequestParam String nome1, @RequestParam String nome2,
			@RequestParam(defaultValue = "dia") String periodo, @RequestParam(defaultValue = "") String lugar) {
		String a = nome1.strip();
		String b = nome2.strip();
		if (a.isEmpty() || b.isEmpty() || a.length() > MAX_NOME || b.length() > MAX_NOME || a.equalsIgnoreCase(b)) {
			return erro("index", HttpStatus.BAD_REQUEST,
					"Escreva dois nomes diferentes, com até " + MAX_NOME + " caracteres cada.");
		}
		// O lugar entra no pedido ao modelo; fica só o que é letra, número e pontuação simples.
		String onde = lugar.replaceAll("[^\\p{L}\\p{N} ,'-]", " ").strip();
		if (onde.length() > MAX_LUGAR) {
			return erro("index", HttpStatus.BAD_REQUEST, "Descreva o lugar com até " + MAX_LUGAR + " caracteres.");
		}
		List<String> temas;
		try {
			temas = juiz.sortearTemas("noite".equals(periodo), onde);
		}
		catch (RuntimeException e) {
			// A mensagem da exceção pode citar o que foi enviado ao modelo; só a classe vai para o log.
			log.warn("Falha ao chamar o modelo: {}", e.getClass().getSimpleName());
			return erro("index", HttpStatus.SERVICE_UNAVAILABLE, SEM_MODELO);
		}
		// Os temas são repartidos de forma alternada, para nenhum jogador ficar só com o começo da lista.
		List<Rodada> rodadasA = new ArrayList<>();
		List<Rodada> rodadasB = new ArrayList<>();
		for (int i = 0; i < temas.size(); i++) {
			((i % 2 == 0) ? rodadasA : rodadasB).add(new Rodada(temas.get(i), null, null, null));
		}
		String id = LocalDateTime.now().format(FORMATO_ID);
		try {
			duelos.criar(id);
			duelos.salvar(new Duelo(id, LocalDate.now(), List.of(new Jogador(a, rodadasA), new Jogador(b, rodadasB)),
					false));
		}
		catch (IOException e) {
			log.warn("Falha ao guardar o duelo: {}", e.getClass().getSimpleName());
			return erro("index", HttpStatus.INTERNAL_SERVER_ERROR, "Não deu para guardar o duelo. Tente de novo.");
		}
		return new ModelAndView("redirect:/duelos/" + id);
	}

	/** Antes do julgamento, a porta de entrada do duelo; depois, o placar. */
	@GetMapping("/duelos/{id}")
	ModelAndView duelo(@PathVariable String id) {
		Duelo duelo = ler(id);
		return new ModelAndView(duelo.julgado() ? "placar" : "duelo", Map.of("duelo", duelo));
	}

	@GetMapping("/duelos/{id}/carta/{numero}")
	ModelAndView carta(@PathVariable String id, @PathVariable int numero) {
		Duelo duelo = ler(id);
		if (numero < 1 || numero > duelo.jogadores().size()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return new ModelAndView("carta", Map.of("duelo", duelo, "jogador", duelo.jogadores().get(numero - 1)));
	}

	@GetMapping("/duelos/{id}/fotos")
	ModelAndView formularioDeFotos(@PathVariable String id) {
		Duelo duelo = ler(id);
		return duelo.julgado() ? new ModelAndView("redirect:/duelos/" + id)
				: new ModelAndView("fotos", Map.of("duelo", duelo));
	}

	@PostMapping("/duelos/{id}/fotos")
	ModelAndView julgar(@PathVariable String id, MultipartHttpServletRequest pedido) {
		Duelo duelo = ler(id);
		if (duelo.julgado()) {
			return new ModelAndView("redirect:/duelos/" + id);
		}
		try {
			// Todas as fotos são conferidas antes de gravar e antes da primeira chamada ao modelo.
			List<List<MimeType>> tipos = new ArrayList<>();
			int enviadas = 0;
			for (int j = 0; j < duelo.jogadores().size(); j++) {
				List<MimeType> doJogador = new ArrayList<>();
				for (int t = 0; t < duelo.jogadores().get(j).rodadas().size(); t++) {
					MultipartFile foto = pedido.getFile(campo(j, t));
					if (foto == null || foto.isEmpty()) {
						doJogador.add(null);
						continue;
					}
					MimeType tipo = tipoDaImagem(foto);
					if (tipo == null) {
						return erroNasFotos(duelo, HttpStatus.BAD_REQUEST, "Envie só fotos em JPEG ou PNG.");
					}
					doJogador.add(tipo);
					enviadas++;
				}
				tipos.add(doJogador);
			}
			if (enviadas == 0) {
				return erroNasFotos(duelo, HttpStatus.BAD_REQUEST, "Envie pelo menos uma foto.");
			}
			List<Jogador> julgados = new ArrayList<>();
			for (int j = 0; j < duelo.jogadores().size(); j++) {
				Jogador jogador = duelo.jogadores().get(j);
				List<Rodada> rodadas = new ArrayList<>();
				for (int t = 0; t < jogador.rodadas().size(); t++) {
					String tema = jogador.rodadas().get(t).tema();
					MimeType tipo = tipos.get(j).get(t);
					if (tipo == null) {
						rodadas.add(new Rodada(tema, null, null, null));
						continue;
					}
					// O nome no disco é nosso; o nome do arquivo enviado não é usado.
					String arquivo = campo(j, t) + (MimeTypeUtils.IMAGE_PNG.equals(tipo) ? ".png" : ".jpg");
					Path destino = duelos.caminhoDaFoto(id, arquivo).orElseThrow(IOException::new);
					pedido.getFile(campo(j, t)).transferTo(destino);
					Optional<Veredito> veredito;
					try {
						veredito = juiz.julgar(tema, tipo, Files.readAllBytes(destino));
					}
					catch (RuntimeException e) {
						// A mensagem da exceção pode citar o que foi enviado ao modelo; só a classe vai para o log.
						log.warn("Falha ao chamar o modelo: {}", e.getClass().getSimpleName());
						return erroNasFotos(duelo, HttpStatus.SERVICE_UNAVAILABLE, SEM_MODELO);
					}
					rodadas.add(new Rodada(tema, arquivo, veredito.map(Veredito::nota).orElse(null),
							veredito.map(Veredito::motivo).orElse(null)));
				}
				julgados.add(new Jogador(jogador.nome(), rodadas));
			}
			duelos.salvar(new Duelo(id, duelo.dia(), julgados, true));
		}
		catch (IOException e) {
			log.warn("Falha ao guardar as fotos: {}", e.getClass().getSimpleName());
			return erroNasFotos(duelo, HttpStatus.INTERNAL_SERVER_ERROR,
					"Não deu para guardar as fotos. Tente enviar de novo.");
		}
		return new ModelAndView("redirect:/duelos/" + id);
	}

	@GetMapping("/duelos/{id}/fotos/{arquivo}")
	ResponseEntity<Resource> foto(@PathVariable String id, @PathVariable String arquivo) {
		Path foto = duelos.foto(id, arquivo).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		MediaType tipo = arquivo.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
		return ResponseEntity.ok().contentType(tipo).body(new FileSystemResource(foto));
	}

	private Duelo ler(String id) {
		return duelos.ler(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	/** O nome do campo do formulário, e também do arquivo no disco, para a foto de um jogador num tema. */
	private static String campo(int jogador, int tema) {
		return "j" + jogador + "t" + tema;
	}

	// O tipo vem do conteúdo do arquivo; o que o navegador declara não é confiável.
	private static MimeType tipoDaImagem(MultipartFile foto) throws IOException {
		byte[] b;
		try (InputStream in = foto.getInputStream()) {
			b = in.readNBytes(8);
		}
		if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
			return MimeTypeUtils.IMAGE_JPEG;
		}
		if (b.length == 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == '\r'
				&& b[5] == '\n' && b[6] == 0x1A && b[7] == '\n') {
			return MimeTypeUtils.IMAGE_PNG;
		}
		return null;
	}

	private static ModelAndView erro(String pagina, HttpStatus status, String mensagem) {
		return new ModelAndView(pagina, Map.of("erro", mensagem), status);
	}

	private static ModelAndView erroNasFotos(Duelo duelo, HttpStatus status, String mensagem) {
		return new ModelAndView("fotos", Map.of("duelo", duelo, "erro", mensagem), status);
	}

	/**
	 * O limite de tamanho é verificado antes de o pedido chegar ao controller, por isso
	 * o tratamento fica num advice.
	 */
	@ControllerAdvice
	static class FotoGrandeDemais {

		@ExceptionHandler(MaxUploadSizeExceededException.class)
		ModelAndView fotoGrandeDemais() {
			return erro("index", HttpStatus.CONTENT_TOO_LARGE,
					"Cada foto pode ter até 20 MB. Volte ao duelo e envie fotos menores.");
		}

	}

}
