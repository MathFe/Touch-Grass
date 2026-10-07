package dev.duelo.fotos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guarda cada duelo numa pasta própria, no computador de quem roda a app:
 * as fotos e um duelo.json com os nomes, os temas e as notas.
 */
@Component
class DueloRepositorio {

	// Os nomes vêm da URL; só estes formatos chegam ao disco.
	private static final Pattern ID = Pattern.compile("\\d{8}-\\d{6}");

	private static final Pattern ARQUIVO = Pattern.compile("j[01]t\\d\\.(jpg|png)");

	private static final String DADOS = "duelo.json";

	private final Path raiz;

	private final JsonMapper json;

	DueloRepositorio(@Value("${duelo.pasta:duelos}") String pasta, JsonMapper json) {
		this.raiz = Path.of(pasta).toAbsolutePath().normalize();
		this.json = json;
	}

	/** Cria a pasta do duelo e devolve o caminho dela. */
	Path criar(String id) throws IOException {
		if (!ID.matcher(id).matches()) {
			throw new IOException("Identificador de duelo inválido");
		}
		Files.createDirectories(raiz);
		return Files.createDirectory(raiz.resolve(id));
	}

	void salvar(Duelo duelo) throws IOException {
		try {
			json.writeValue(raiz.resolve(duelo.id()).resolve(DADOS), duelo);
		}
		catch (JacksonException e) {
			throw new IOException("Falha ao gravar o duelo", e);
		}
	}

	Optional<Duelo> ler(String id) {
		if (!ID.matcher(id).matches()) {
			return Optional.empty();
		}
		Path dados = raiz.resolve(id).resolve(DADOS);
		if (!Files.isRegularFile(dados)) {
			return Optional.empty();
		}
		try {
			return Optional.of(json.readValue(dados, Duelo.class));
		}
		catch (JacksonException e) {
			return Optional.empty();
		}
	}

	/** Onde gravar uma foto do duelo; vazio quando o nome não é um dos nossos. */
	Optional<Path> caminhoDaFoto(String id, String arquivo) {
		if (!ID.matcher(id).matches() || !ARQUIVO.matcher(arquivo).matches()) {
			return Optional.empty();
		}
		return Optional.of(raiz.resolve(id).resolve(arquivo));
	}

	Optional<Path> foto(String id, String arquivo) {
		return caminhoDaFoto(id, arquivo).filter(Files::isRegularFile);
	}

}
