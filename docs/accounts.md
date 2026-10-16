# Contas (Supabase Auth)

Na primeira abertura o Recly pede para criar uma conta (e-mail e senha ou Google). Depois disso a
pessoa entra direto. A sessão fica no aparelho, criptografada com uma chave do Android Keystore, e
continua valendo **sem internet**. O app só desloga quando o servidor recusa o refresh token (conta
excluída, senha trocada, sessão revogada) ou quando a pessoa toca em "Sair da conta".

## Código

| Arquivo | Papel |
|---|---|
| `account/SupabaseAuthClient.kt` | Chamadas REST ao Supabase Auth (cadastro, login, Google, refresh, recuperar senha, reenviar confirmação, sair). Sem SDK. |
| `account/AccountManager.kt` | Ponto único: sessão em memória, rede fora da thread principal, renovação do token, sair, excluir conta. |
| `account/SessionStore.kt` | Sessão criptografada (AES-GCM, chave no Keystore). Se a chave sumir, a pessoa só entra de novo. |
| `account/AuthModels.kt` | Validação dos campos e tradução das mensagens de erro do Supabase para português. Coberto por `AccountRulesTest`. |
| `account/GoogleSignIn.kt` | "Continuar com Google" pelo Credential Manager, com nonce (o Google recebe o hash, o Supabase confere). |
| `account/AuthActivity.kt` + `layout/activity_auth.xml` | Tela única com os modos criar conta, entrar, recuperar senha e confirmar e-mail. |
| `account/AccountGate.kt` | Portão usado pela tela inicial e pelo editor: sem conta, abre o cadastro e volta para onde a pessoa ia. |

## 1. Projeto no Supabase

O projeto oficial já está configurado: `SUPABASE_URL` e `SUPABASE_ANON_KEY` ficam no
`gradle.properties` da raiz e o app usa esses valores sem nenhum passo manual. O banco é criado pelas
migrations em `supabase/migrations/` (já aplicadas no projeto oficial):

| Migration | O que faz |
|---|---|
| `…01_delete_user.sql` | Função `delete_user()` usada por **Ajustes › Excluir conta**. |
| `…02_profiles.sql` | Tabela `profiles` (nome e foto), criada sozinha no cadastro e apagada junto com a conta. RLS: cada pessoa só lê e edita o próprio perfil. |
| `…03_lock_rls_auto_enable.sql` | Fecha a função padrão `rls_auto_enable()` para quem chama pela API. |

Para apontar para outro projeto (ou um Supabase local), coloque no `local.properties`, que tem
prioridade e não vai para o git:

```properties
SUPABASE_URL=https://SEU-PROJETO.supabase.co
SUPABASE_ANON_KEY=eyJ...
GOOGLE_WEB_CLIENT_ID=123456789-abc.apps.googleusercontent.com
```

Num projeto novo, rode as migrations em ordem (SQL Editor ou `supabase db push`). A chave anon é
pública por desenho; o que protege os dados são as políticas RLS. **Nunca** coloque a chave
`service_role` no app.

Um build **release** sem esses dois valores falha de propósito (senão ninguém conseguiria entrar).

Para testar o editor sem passar pelo login, só no **debug**, adicione ao `local.properties`:

```properties
RECLY_SKIP_LOGIN=true
```

O release ignora essa linha.

### Entrar sem conta (modo visitante)

A tela de conta tem o botão **Entrar sem conta**, em qualquer build. O visitante usa o editor
normalmente; o que gasta créditos no servidor (legendas automáticas pelo backend) continua pedindo
conta, porque sem sessão o app não tem token para mandar. O botão de perfil da tela inicial oferece
"Entrar ou criar conta" ao visitante. Entrar ou criar conta desliga o modo visitante, e sair da conta
volta para a tela de login.
Sem `SUPABASE_URL`/`SUPABASE_ANON_KEY` o app de debug mostra um aviso na tela de conta. Em build de debug aparece
"Continuar sem conta" para você seguir desenvolvendo; em release esse botão não existe.

### Supabase local (opcional)

Com `supabase start` no PC, use `SUPABASE_URL=http://10.0.2.2:54321` no emulador (ou o IP do PC no
celular). HTTP só é aceito para `127.0.0.1`, `localhost` e `10.0.2.2`, e o Android ainda exige liberar
tráfego sem TLS para esse host num `network_security_config` de debug; em produção use sempre HTTPS.

## 2. E-mail e senha

Em **Authentication › Providers › Email**:

- **Enable Email provider**: ligado.
- **Confirm email**: ligado é o recomendado. O app mostra a tela "Confirme seu e-mail" com
  reenvio (espera de 60 s) e "Já confirmei, entrar". Desligado, a pessoa entra logo após o cadastro.
- **Minimum password length**: 8. O app já exige 8 caracteres com letras e números.

Em **Authentication › URL Configuration**, ajuste o **Site URL**. É para lá que vão os links de
confirmação e de "Esqueci a senha"; a página padrão do Supabase já permite criar a senha nova.

Os modelos de e-mail ficam em **Authentication › Email Templates** (dá para traduzir para português).

## 3. Google

1. No Google Cloud Console (o mesmo projeto dos seus créditos), em **APIs e serviços › Credenciais**:
   - crie um **ID do cliente OAuth do tipo Aplicativo da Web** (este vira o `GOOGLE_WEB_CLIENT_ID`);
   - crie um **ID do cliente do tipo Android** com o pacote `com.recly.editor` e o SHA-1 da sua chave
     de assinatura (`./gradlew signingReport`). Faça um para a chave de debug e outro para a de release.
2. No Supabase, **Authentication › Providers › Google**: ligue, cole o **Client ID** e o **Client Secret**
   do cliente Web e deixe **Skip nonce checks** desligado (o app manda o nonce).
3. Se `GOOGLE_WEB_CLIENT_ID` ficar vazio, o botão do Google some e só e-mail aparece.

## 4. Excluir conta (exigido pelo Google Play)

O Play exige que apps com cadastro permitam excluir a conta pelo app. O botão está em
**Ajustes › Excluir conta** e chama a função `delete_user()` (migration `…01_delete_user.sql`).

Sem essa função o app avisa "A exclusão de conta ainda não foi ativada no servidor". O Play também
pede um link na web para pedir a exclusão; ele vai na ficha da loja.

## 5. Perfil

O nome digitado no cadastro vai em `user_metadata.full_name` e o trigger `on_auth_user_created`
copia para `public.profiles` (no login com Google usa `name` e `picture`). Para ler o perfil pelo app:
`GET /rest/v1/profiles?select=*` com `apikey` e `Authorization: Bearer <access token>`; a RLS já
devolve só a linha da pessoa.

## Usar a conta em outros serviços

`AccountManager.freshAccessToken(context)` (fora da thread principal) devolve um access token
válido, renovando antes se preciso. Mande como `Authorization: Bearer <token>` para o seu backend
(ex.: o de legendas) e valide o JWT com o segredo do projeto Supabase.
