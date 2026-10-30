alter table movimentacao add column id_externo varchar(120);
alter table movimentacao add constraint uk_movimentacao_id_externo unique (id_externo);

alter table conta_conectada add column id_externo varchar(100);
alter table conta_conectada add constraint uk_conta_conectada_item_id_externo unique (item_id, id_externo);

alter table investimento add column id_externo varchar(100);
alter table investimento add constraint uk_investimento_item_id_externo unique (item_id, id_externo);
