create table movimentacao (
    id uuid not null,
    data date,
    recorrente boolean not null,
    valor numeric(38,2),
    categoria varchar(255),
    conta varchar(255),
    descricao varchar(255),
    tipo varchar(255),
    primary key (id)
);

create table limite_categoria (
    categoria varchar(60) not null,
    limite numeric(38,2),
    primary key (categoria)
);

create table meta_financeira (
    id uuid not null,
    prazo date,
    valor_alvo numeric(38,2),
    valor_atual numeric(38,2),
    nome varchar(255),
    primary key (id)
);

create table conta_conectada (
    id uuid not null,
    fatura_atual numeric(38,2),
    limite_credito numeric(38,2),
    saldo numeric(38,2),
    atualizado_em timestamp(6),
    instituicao varchar(255),
    item_id varchar(255),
    nome varchar(255),
    tipo varchar(255),
    primary key (id)
);

create table investimento (
    id uuid not null,
    rentabilidade numeric(38,2),
    valor_investido numeric(38,2),
    atualizado_em timestamp(6),
    instituicao varchar(255),
    item_id varchar(255),
    nome varchar(255),
    tipo varchar(255),
    primary key (id)
);
