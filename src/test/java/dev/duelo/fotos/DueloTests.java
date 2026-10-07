package dev.duelo.fotos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.duelo.fotos.Duelo.Jogador;
import dev.duelo.fotos.Duelo.Rodada;
import tools.jackson.databind.json.JsonMapper;

class DueloTests {

	private static final String ID = "20261006-221500";

	@TempDir
	Path pasta;

	@Test
	void somaAsNotasEContaZeroParaFotoSemNota() {
		Duelo duelo = duelo(true);
		assertThat(duelo.jogadores().get(0).total()).isEqualTo(15);
		assertThat(duelo.jogadores().get(1).total()).isEqualTo(9);
		assertThat(duelo.vencedor()).isZero();
	}

	@Test
	void semNotasDaEmpate() {
		Jogador semFotos = new Jogador("Ana", List.of(new Rodada("uma sombra", null, null, null)));
		Jogador tambemSem = new Jogador("Bia", List.of(new Rodada("uma luz", null, null, null)));
		assertThat(new Duelo(ID, LocalDate.of(2026, 10, 6), List.of(semFotos, tambemSem), true).vencedor()).isEqualTo(-1);
	}

	@Test
	void guardaELeODuelo() throws Exception {
		DueloRepositorio duelos = new DueloRepositorio(pasta.toString(), JsonMapper.builder().build());
		Path doDuelo = duelos.criar(ID);
		Files.write(doDuelo.resolve("j0t0.jpg"), new byte[] { 1 });

		duelos.salvar(duelo(true));

		assertThat(duelos.ler(ID)).contains(duelo(true));
		assertThat(duelos.foto(ID, "j0t0.jpg")).isPresent();
		assertThat(duelos.foto(ID, "j1t0.png")).isEmpty();
	}

	@Test
	void naoSaiDaPastaDosDuelos() throws Exception {
		DueloRepositorio duelos = new DueloRepositorio(pasta.toString(), JsonMapper.builder().build());
		duelos.criar(ID);
		duelos.salvar(duelo(false));

		assertThat(duelos.ler("..")).isEmpty();
		assertThat(duelos.foto(ID, "duelo.json")).isEmpty();
		assertThat(duelos.caminhoDaFoto(ID, "../" + ID + "/j0t0.jpg")).isEmpty();
	}

	private static Duelo duelo(boolean julgado) {
		Jogador ana = new Jogador("Ana", List.of(new Rodada("uma sombra", "j0t0.jpg", 8, "Há uma sombra."),
				new Rodada("algo redondo", "j0t1.jpg", 7, "Há uma roda."), new Rodada("um reflexo", null, null, null)));
		Jogador bia = new Jogador("Bia", List.of(new Rodada("uma luz", "j1t0.png", 9, "Há um poste aceso."),
				new Rodada("algo velho", "j1t1.jpg", null, null)));
		return new Duelo(ID, LocalDate.of(2026, 10, 6), List.of(ana, bia), julgado);
	}

}
