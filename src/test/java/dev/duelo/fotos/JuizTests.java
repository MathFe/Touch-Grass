package dev.duelo.fotos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.duelo.fotos.Juiz.Veredito;
import tools.jackson.databind.json.JsonMapper;

class JuizTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final List<String> CANDIDATOS = List.of("Algo redondo", "Algo torto", "Algo de ferro", "Algo que pisca");

	@Test
	void ficaSoComOsTemasDaListaNaOrdemDoModelo() {
		String resposta = """
				Aqui estão os temas:
				1. Algo que pisca
				- algo redondo.
				* Algo Redondo
				Algo inventado pelo modelo
				""";
		assertThat(Juiz.escolhidos(resposta, CANDIDATOS, 2)).containsExactly("Algo que pisca", "Algo redondo");
	}

	@Test
	void completaComOsCandidatosQuandoOModeloEscolheDeMenos() {
		assertThat(Juiz.escolhidos("Algo de ferro\nqualquer coisa", CANDIDATOS, 3)).containsExactly("Algo de ferro",
				"Algo redondo", "Algo torto");
		assertThat(Juiz.escolhidos(null, CANDIDATOS, 2)).containsExactly("Algo redondo", "Algo torto");
	}

	@Test
	void leOVereditoMesmoCercadoDeTexto() {
		String resposta = "```json\n{\"nota\": 7, \"motivo\": \" Há uma sombra no chão. \"}\n```";
		assertThat(Juiz.vereditoDe(resposta, JSON)).contains(new Veredito(7, "Há uma sombra no chão."));
	}

	@Test
	void vereditoSemOsCamposEsperadosEFalha() {
		assertThat(Juiz.vereditoDe("{\"nota\": 7}", JSON)).isEmpty();
		assertThat(Juiz.vereditoDe("{\"motivo\": \"bonita\"}", JSON)).isEmpty();
		assertThat(Juiz.vereditoDe("{\"nota\": 11, \"motivo\": \"bonita\"}", JSON)).isEmpty();
		assertThat(Juiz.vereditoDe("{\"nota\": \"alta\", \"motivo\": \"bonita\"}", JSON)).isEmpty();
		assertThat(Juiz.vereditoDe("{\"nota\": 7, \"motivo\": \"  \"}", JSON)).isEmpty();
		assertThat(Juiz.vereditoDe("nota 7, foto bonita", JSON)).isEmpty();
		assertThat(Juiz.vereditoDe(null, JSON)).isEmpty();
	}

}
