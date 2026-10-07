package dev.duelo.fotos;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import dev.duelo.fotos.Duelo.Jogador;
import dev.duelo.fotos.Duelo.Rodada;

// Todos os casos são resolvidos antes da chamada ao modelo, então os testes passam com o Ollama fechado.
@SpringBootTest(properties = "duelo.pasta=target/duelos-de-teste")
@AutoConfigureMockMvc
class DueloControllerTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DueloRepositorio duelos;

	@Test
	void mostraOFormulario() throws Exception {
		mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("index"));
	}

	@Test
	void recusaNomeEmBranco() throws Exception {
		mvc.perform(post("/duelos").param("nome1", "Ana").param("nome2", "   "))
			.andExpect(status().isBadRequest())
			.andExpect(model().attributeExists("erro"));
	}

	@Test
	void recusaNomesIguais() throws Exception {
		mvc.perform(post("/duelos").param("nome1", "Ana").param("nome2", "ana"))
			.andExpect(status().isBadRequest())
			.andExpect(model().attributeExists("erro"));
	}

	@Test
	void mostraOPlacarDeUmDueloJulgado() throws Exception {
		String id = "20261006-221500";
		Files.createDirectories(Path.of("target/duelos-de-teste", id));
		Jogador ana = new Jogador("Ana", List.of(new Rodada("uma sombra", "j0t0.jpg", 8, "Há uma sombra."),
				new Rodada("um reflexo", null, null, null)));
		Jogador bia = new Jogador("Bia", List.of(new Rodada("uma luz", "j1t0.png", null, null)));
		duelos.salvar(new Duelo(id, LocalDate.of(2026, 10, 6), List.of(ana, bia), true));

		mvc.perform(get("/duelos/" + id))
			.andExpect(status().isOk())
			.andExpect(view().name("placar"))
			.andExpect(content().string(containsString("Ana venceu")))
			.andExpect(content().string(containsString("Placar: 8 × 0")));
	}

	@Test
	void mostraAsPaginasDeUmDueloAindaNaoJulgado() throws Exception {
		String id = "20261006-221501";
		Files.createDirectories(Path.of("target/duelos-de-teste", id));
		Jogador ana = new Jogador("Ana", List.of(new Rodada("uma sombra", null, null, null)));
		Jogador bia = new Jogador("Bia", List.of(new Rodada("uma luz", null, null, null)));
		duelos.salvar(new Duelo(id, LocalDate.of(2026, 10, 6), List.of(ana, bia), false));

		mvc.perform(get("/duelos/" + id)).andExpect(status().isOk()).andExpect(view().name("duelo"));
		mvc.perform(get("/duelos/" + id + "/carta/2"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("uma luz")))
			.andExpect(content().string(not(containsString("uma sombra"))));
		mvc.perform(get("/duelos/" + id + "/carta/3")).andExpect(status().isNotFound());
		mvc.perform(get("/duelos/" + id + "/fotos")).andExpect(status().isOk()).andExpect(view().name("fotos"));
	}

	@Test
	void dueloQueNaoExisteDa404() throws Exception {
		mvc.perform(get("/duelos/20000101-000000")).andExpect(status().isNotFound());
		mvc.perform(get("/duelos/20000101-000000/carta/1")).andExpect(status().isNotFound());
		mvc.perform(get("/duelos/20000101-000000/fotos")).andExpect(status().isNotFound());
		mvc.perform(get("/duelos/20000101-000000/fotos/j0t0.jpg")).andExpect(status().isNotFound());
	}

}
