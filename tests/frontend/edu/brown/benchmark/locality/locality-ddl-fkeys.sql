CREATE TABLE TABLEA (
   A_ID 	BIGINT NOT NULL,
   A_VALUE 	VARCHAR(64),
   PRIMARY KEY (A_ID)
);

CREATE TABLE TABLEB (
   B_ID 	BIGINT NOT NULL,
   B_A_ID 	BIGINT NOT NULL REFERENCES TABLEA (A_ID),
   B_VALUE 	VARCHAR(64),
   PRIMARY KEY (B_ID, B_A_ID)
);
