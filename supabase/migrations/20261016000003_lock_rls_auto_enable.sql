-- rls_auto_enable() é a função padrão do Supabase que liga RLS em tabelas novas (event trigger).
-- Ninguém de fora precisa chamá-la pela API.
revoke execute on function public.rls_auto_enable() from public, anon, authenticated;
