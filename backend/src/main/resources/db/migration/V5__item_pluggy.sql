create table item_pluggy (
    item_id varchar(100) not null,
    nome varchar(60),
    instituicao varchar(80),
    status_pluggy varchar(40),
    consentimento_expira_em timestamp(6),
    ultima_sincronizacao timestamp(6),
    ultimo_erro varchar(255),
    criado_em timestamp(6) not null,
    primary key (item_id)
);
