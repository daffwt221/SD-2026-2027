
package tpf;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public final class CommandRegistrar {

    private static final String GUILD_ID = "1570860151370547262";

    private CommandRegistrar() {
        // Classe utilitária
    }

    public static void registar(Guild guild) {

        guild.updateCommands().addCommands(

            Commands.slash("registar", "Registar texto no arquivo")
                .addOption(
                    OptionType.STRING,
                    "texto",
                    "Texto a registar",
                    true
                ),

            Commands.slash("estado", "Consultar o estado do arquivo"),

            Commands.slash("config", "Configurar o arquivo")
                .addSubcommands(
                    new SubcommandData("r", "Configurar número de réplicas")
                        .addOption(
                            OptionType.INTEGER,
                            "n",
                            "Número de réplicas",
                            true
                        )
                ),

            Commands.slash("recuperar", "Recuperar um intervalo de registos")
                .addOption(
                    OptionType.INTEGER,
                    "de",
                    "Início do intervalo",
                    true
                )
                .addOption(
                    OptionType.INTEGER,
                    "ate",
                    "Fim do intervalo",
                    true
                ),

            Commands.slash(
                "replica-token",
                "Solicitar um token de réplica"
            ),

            Commands.slash("replica-remover", "Remover uma réplica")
                .addOption(
                    OptionType.STRING,
                    "id",
                    "Identificador da réplica",
                    true
                ),

            Commands.slash("rajada", "Solicitar uma rajada de mensagens")
                .addOption(
                    OptionType.INTEGER,
                    "n",
                    "Número de mensagens",
                    true
                )
                .addOption(
                    OptionType.INTEGER,
                    "intervaloms",
                    "Intervalo em milissegundos",
                    true
                )

        ).complete();

        System.out.println("7 comandos atualizados com sucesso.");
    }

    public static void main(String[] args) {

        String token = System.getenv("DISCORD_TOKEN");

        if (token == null || token.isBlank()) {
            System.err.println("Erro: DISCORD_TOKEN não definido.");
            return;
        }

        JDA jda = null;

        try {
            jda = JDABuilder.createDefault(token).build();

            jda.awaitReady();

            Guild guild = jda.getGuildById(GUILD_ID);

            if (guild == null) {
                System.err.println("Servidor Discord não encontrado.");
                return;
            }

            registar(guild);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Inicialização interrompida.");

        } catch (Exception e) {
            System.err.println(
                "Erro ao atualizar comandos: " + e.getMessage()
            );

        } finally {
            if (jda != null) {
                jda.shutdown();
            }
        }
    }
}
