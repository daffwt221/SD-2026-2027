package tpf;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class LeaderBot extends ListenerAdapter {

    private static final String GUILD_ID = "1570860151370547262";
    private static final String CANAL_ARQUIVO = "arquivo";
    private static final String PAPEL_ADMIN = "admin-arquivo";

    public static void main(String[] args) {
        String token = System.getenv("DISCORD_TOKEN");

        if (token == null || token.isBlank()) {
            System.err.println("Erro: variável DISCORD_TOKEN não definida.");
            return;
        }

        try {
            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new LeaderBot())
                    .build();

            jda.awaitReady();

            Guild guild = jda.getGuildById(GUILD_ID);
            if (guild == null) {
                System.err.println("Erro: servidor Discord não encontrado.");
                jda.shutdown();
                return;
            }

            registarComandos(guild);
            System.out.println("Bot ligado ao Discord.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Inicialização interrompida.");
        } catch (Exception e) {
            System.err.println("Erro ao iniciar o bot: " + e.getMessage());
        }
    }

    private static void registarComandos(Guild guild) {
        guild.updateCommands().addCommands(
                Commands.slash("registar", "Registar texto no arquivo")
                        .addOption(OptionType.STRING, "texto", "Texto a registar", true),

                Commands.slash("estado", "Consultar o estado do arquivo"),

                Commands.slash("config", "Configurar o arquivo")
                        .addSubcommands(new SubcommandData("r", "Configurar número de réplicas")
                                .addOption(OptionType.INTEGER, "n", "Número de réplicas", true)),

                Commands.slash("recuperar", "Recuperar um intervalo de registos")
                        .addOption(OptionType.INTEGER, "de", "Início do intervalo", true)
                        .addOption(OptionType.INTEGER, "ate", "Fim do intervalo", true),

                Commands.slash("replica-token", "Solicitar um token de réplica"),

                Commands.slash("replica-remover", "Remover uma réplica")
                        .addOption(OptionType.STRING, "id", "Identificador da réplica", true),

                Commands.slash("rajada", "Solicitar uma rajada de mensagens")
                        .addOption(OptionType.INTEGER, "n", "Número de mensagens", true)
                        .addOption(OptionType.INTEGER, "intervaloms", "Intervalo em milissegundos", true)
        ).queue(
                success -> System.out.println("7 comandos registados com sucesso."),
                error -> System.err.println("Erro ao registar comandos: " + error.getMessage())
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || !GUILD_ID.equals(event.getGuild().getId())) {
            event.reply("Este comando só está disponível no servidor de teste.")
                    .setEphemeral(true).queue();
            return;
        }

        switch (event.getName()) {
            case "registar" -> tratarRegistar(event);
            case "estado" -> event.reply("Pedido /estado recebido. Estado do arquivo ainda não implementado.")
                    .queue();
            case "config" -> tratarConfig(event);
            case "recuperar" -> tratarRecuperar(event);
            case "replica-token" -> tratarReplicaToken(event);
            case "replica-remover" -> tratarReplicaRemover(event);
            case "rajada" -> tratarRajada(event);
            default -> event.reply("Comando desconhecido.").setEphemeral(true).queue();
        }
    }

    private void tratarRegistar(SlashCommandInteractionEvent event) {
        OptionMapping opcao = event.getOption("texto");
        String texto = opcao == null ? "" : opcao.getAsString().trim();

        if (texto.isBlank() || texto.length() > 1500) {
            event.reply("O texto deve ter entre 1 e 1500 caracteres.")
                    .setEphemeral(true).queue();
            return;
        }

        event.reply("/registar recebido: " + texto + "\nRegisto ainda não implementado.").queue();
    }

    private void tratarConfig(SlashCommandInteractionEvent event) {
        if (!autorizarAdmin(event)) return;

        OptionMapping opcao = event.getOption("n");
        if (!"r".equals(event.getSubcommandName()) || opcao == null || opcao.getAsLong() < 1) {
            event.reply("Uso inválido. Indica /config r com n maior que zero.")
                    .setEphemeral(true).queue();
            return;
        }

        event.reply("/config r recebido: n=" + opcao.getAsLong()
                + ". Configuração ainda não implementada.").queue();
    }

    private void tratarRecuperar(SlashCommandInteractionEvent event) {
        OptionMapping inicio = event.getOption("de");
        OptionMapping fim = event.getOption("ate");

        if (inicio == null || fim == null) {
            event.reply("Indica os limites de e ate.").setEphemeral(true).queue();
            return;
        }

        long de = inicio.getAsLong();
        long ate = fim.getAsLong();
        if (de < 1 || ate < de) {
            event.reply("Intervalo inválido: de deve ser positivo e não pode ser maior que ate.")
                    .setEphemeral(true).queue();
            return;
        }

        event.reply("/recuperar recebido: de=" + de + ", ate=" + ate
                + ". Recuperação ainda não implementada.").queue();
    }

    private void tratarReplicaToken(SlashCommandInteractionEvent event) {
        if (!autorizarAdmin(event)) return;

        event.reply("/replica-token recebido. Geração de tokens ainda não implementada.")
                .setEphemeral(true).queue();
    }

    private void tratarReplicaRemover(SlashCommandInteractionEvent event) {
        if (!autorizarAdmin(event)) return;

        OptionMapping opcao = event.getOption("id");
        String id = opcao == null ? "" : opcao.getAsString().trim();
        if (id.isBlank()) {
            event.reply("O identificador da réplica não pode estar vazio.")
                    .setEphemeral(true).queue();
            return;
        }

        event.reply("/replica-remover recebido: id=" + id
                + ". Remoção ainda não implementada.").queue();
    }

    private void tratarRajada(SlashCommandInteractionEvent event) {
        if (!autorizarAdmin(event)) return;

        OptionMapping quantidade = event.getOption("n");
        OptionMapping intervalo = event.getOption("intervaloms");
        if (quantidade == null || intervalo == null
                || quantidade.getAsLong() < 1 || intervalo.getAsLong() < 1) {
            event.reply("Indica n e intervaloMs maiores que zero.")
                    .setEphemeral(true).queue();
            return;
        }

        event.reply("/rajada recebido: n=" + quantidade.getAsLong()
                + ", intervaloMs=" + intervalo.getAsLong()
                + ". Rajada ainda não implementada.").queue();
    }

    private boolean autorizarAdmin(SlashCommandInteractionEvent event) {
        boolean autorizado = event.getMember() != null
                && event.getMember().getRoles().stream()
                .anyMatch(role -> PAPEL_ADMIN.equals(role.getName()));

        if (!autorizado) {
            event.reply("Sem permissão: é necessário o papel admin-arquivo.")
                    .setEphemeral(true).queue();
        }
        return autorizado;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }
        if (!GUILD_ID.equals(event.getGuild().getId())
                || !CANAL_ARQUIVO.equals(event.getChannel().getName())) {
            return;
        }

        System.out.println("Mensagem em #arquivo de " + event.getAuthor().getName()
                + ": " + event.getMessage().getContentRaw());
        event.getChannel().sendMessage("Mensagem recebida em #arquivo. Arquivo ainda não implementado.")
                .queue();
    }
}
