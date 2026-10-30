alter table movimentacao add column recorrencia_origem_id uuid;
alter table movimentacao add constraint uk_movimentacao_recorrencia_origem unique (recorrencia_origem_id);
alter table movimentacao add constraint fk_movimentacao_recorrencia_origem
    foreign key (recorrencia_origem_id) references movimentacao (id) on delete set null;
