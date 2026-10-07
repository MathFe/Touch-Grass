package dev.duelo.fotos;

import java.time.LocalDate;
import java.util.List;

/**
 * Um duelo de fotos: dois jogadores, cada um com os seus temas secretos, e depois
 * do passeio as fotos com a nota e o motivo do juiz.
 */
record Duelo(String id, LocalDate dia, List<Jogador> jogadores, boolean julgado) {

	record Jogador(String nome, List<Rodada> rodadas) {

		// Público porque o Thymeleaf só chama métodos públicos.
		public int total() {
			return rodadas.stream().mapToInt(r -> (r.nota() != null) ? r.nota() : 0).sum();
		}

	}

	/**
	 * O arquivo é null quando o jogador não trouxe foto para o tema. A nota é null
	 * enquanto o duelo não foi julgado, e também quando o juiz não conseguiu julgar a foto.
	 */
	record Rodada(String tema, String arquivo, Integer nota, String motivo) {
	}

	/** O índice de quem venceu, ou -1 no empate. */
	public int vencedor() {
		int diferenca = jogadores.get(0).total() - jogadores.get(1).total();
		return (diferenca == 0) ? -1 : (diferenca > 0) ? 0 : 1;
	}

}
