package dev.duelo.fotos;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * O Gemma local nos dois papéis do jogo: escolhe os temas antes do passeio e
 * julga cada foto depois. Cada foto vai sozinha numa chamada.
 * Os temas saem de uma lista escrita à mão; o modelo só escolhe os que combinam com o passeio.
 * Um veredito fora do formato pedido é falha, não resposta vazia: tenta-se mais uma vez e desiste.
 * Falha de conexão sai como exceção, para quem chama avisar que o Ollama está fechado.
 */
@Component
class Juiz {

	static final int TEMAS_POR_JOGADOR = 5;

	private static final int TEMAS = 2 * TEMAS_POR_JOGADOR;

	// O modelo escolhe os temas dentre este tanto de candidatos, sorteados da lista a cada duelo.
	private static final int CANDIDATOS = 24;

	private static final int TENTATIVAS = 2;

	private static final Logger log = LoggerFactory.getLogger(Juiz.class);

	private static final Pattern MARCADOR = Pattern.compile("^\\s*(\\d+\\s*[.)-]|[-*•])\\s*");

	private final ChatClient chat;

	private final JsonMapper json;

	private final List<String> temasDeDia = new ArrayList<>();

	private final List<String> temasDeNoite = new ArrayList<>();

	private final List<String> temasDeSempre = new ArrayList<>();

	Juiz(ChatClient.Builder builder, JsonMapper json) throws IOException {
		this.chat = builder.build();
		this.json = json;
		try (InputStream in = Juiz.class.getResourceAsStream("/temas.txt")) {
			if (in == null) {
				throw new IOException("Lista de temas ausente");
			}
			BufferedReader leitor = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String linha : leitor.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).toList()) {
				String[] campos = linha.split("\\|", 2);
				List<String> destino = switch (campos[0]) {
					case "dia" -> temasDeDia;
					case "noite" -> temasDeNoite;
					case "sempre" -> temasDeSempre;
					default -> throw new IOException("Período desconhecido na lista de temas: " + campos[0]);
				};
				destino.add(campos[1].strip());
			}
		}
	}

	/**
	 * Dez temas diferentes entre si. Os candidatos são todos os temas do período mais um
	 * sorteio dos que servem a qualquer hora; o modelo escolhe entre eles. O que ele
	 * devolver fora da lista é ignorado, e o que faltar é completado com os candidatos.
	 */
	List<String> sortearTemas(boolean noite, String lugar) {
		List<String> candidatos = new ArrayList<>(noite ? temasDeNoite : temasDeDia);
		List<String> gerais = new ArrayList<>(temasDeSempre);
		Collections.shuffle(gerais);
		candidatos.addAll(gerais.subList(0, CANDIDATOS - candidatos.size()));
		Collections.shuffle(candidatos);
		String pedido = "Você prepara um jogo de fotos para duas pessoas que vão sair para um passeio "
				+ (noite ? "à noite" : "de dia") + (lugar.isEmpty() ? "" : ", neste tipo de lugar: " + lugar) + ". "
				+ "Abaixo há uma lista de temas de foto, um por linha. "
				+ "Escolha os " + TEMAS + " temas que mais combinam com esse passeio: "
				+ "os que dá para encontrar ali e que rendem fotos diferentes entre si. "
				+ "Copie cada tema escolhido exatamente como está escrito, um por linha, sem numeração, "
				+ "sem marcador e sem nenhuma outra frase.\n\n" + String.join("\n", candidatos);
		String resposta = chat.prompt().user(pedido).call().content();
		log.info("O modelo escolheu {} temas da lista", Math.min(reconhecidos(resposta, candidatos).size(), TEMAS));
		return escolhidos(resposta, candidatos, TEMAS);
	}

	/** A nota e o motivo para uma foto, ou vazio quando o modelo não responde no formato pedido. */
	Optional<Veredito> julgar(String tema, MimeType tipo, byte[] foto) {
		String pedido = "Você é o juiz de um jogo de fotos. O tema desta foto é: " + tema + ". "
				+ "Olhe a foto e dê uma nota para o quanto ela cumpre o tema. "
				+ "A nota é um número inteiro de 0 a 10: 0 quando a foto não tem relação com o tema, "
				+ "10 quando cumpre o tema por inteiro. Notas do meio valem para fotos que cumprem em parte. "
				+ "Responda só com um objeto JSON com dois campos: nota, com o número, e motivo, "
				+ "com uma frase curta em português que diga o que há na foto que justifica a nota. "
				+ "No motivo, fale só do que está visível, não descreva a aparência de pessoas "
				+ "e não dê nome de lugar nem de espécie.";
		for (int i = 0; i < TENTATIVAS; i++) {
			String resposta = chat.prompt()
				.user(u -> u.text(pedido).media(tipo, new ByteArrayResource(foto)))
				.call()
				.content();
			Optional<Veredito> veredito = vereditoDe(resposta, json);
			if (veredito.isPresent()) {
				return veredito;
			}
			log.warn("O modelo devolveu um veredito fora do formato");
		}
		return Optional.empty();
	}

	/** Os temas da resposta que estão entre os candidatos, completados com os candidatos até a quantidade pedida. */
	static List<String> escolhidos(String resposta, List<String> candidatos, int quantos) {
		Set<String> temas = reconhecidos(resposta, candidatos);
		for (String candidato : candidatos) {
			temas.add(candidato);
		}
		return new ArrayList<>(temas).subList(0, Math.min(quantos, temas.size()));
	}

	/** As linhas da resposta que são, letra por letra, um dos candidatos; sem repetidos e na ordem em que vieram. */
	private static Set<String> reconhecidos(String resposta, List<String> candidatos) {
		Map<String, String> porChave = new LinkedHashMap<>();
		for (String candidato : candidatos) {
			porChave.put(candidato.toLowerCase(Locale.ROOT), candidato);
		}
		Set<String> temas = new LinkedHashSet<>();
		if (resposta != null) {
			for (String linha : resposta.split("\\R")) {
				String tema = MARCADOR.matcher(linha).replaceFirst("").strip().replaceAll("[.;]+$", "");
				String candidato = porChave.get(tema.toLowerCase(Locale.ROOT));
				if (candidato != null) {
					temas.add(candidato);
				}
			}
		}
		return temas;
	}

	/** Lê o JSON da resposta, mesmo cercado de texto; sem os dois campos esperados, é falha. */
	static Optional<Veredito> vereditoDe(String resposta, JsonMapper json) {
		if (resposta == null) {
			return Optional.empty();
		}
		int inicio = resposta.indexOf('{');
		int fim = resposta.lastIndexOf('}');
		if (inicio < 0 || fim < inicio) {
			return Optional.empty();
		}
		Veredito veredito;
		try {
			veredito = json.readValue(resposta.substring(inicio, fim + 1), Veredito.class);
		}
		catch (JacksonException e) {
			return Optional.empty();
		}
		if (veredito == null || veredito.nota() == null || veredito.nota() < 0 || veredito.nota() > 10
				|| veredito.motivo() == null || veredito.motivo().isBlank()) {
			return Optional.empty();
		}
		return Optional.of(new Veredito(veredito.nota(), veredito.motivo().strip()));
	}

	record Veredito(Integer nota, String motivo) {
	}

}
