CREATE TABLE loanRequests (
  id SERIAL PRIMARY KEY,
  customerId INT NOT NULL,
  requestedValue DECIMAL(10,2) NOT NULL,
  termInMonths INT,
  status VARCHAR(255) DEFAULT 'pending',
  approvedAt DATE,
  createdAt DATE NOT NULL DEFAULT CURRENT_DATE,
  updatedAt DATE NOT NULL DEFAULT CURRENT_DATE
);

CREATE TABLE loans (
  id SERIAL PRIMARY KEY,
  requestId INT NOT NULL,
  customerId INT NOT NULL,
  approvedValue DECIMAL(10,2) NOT NULL,
  interestRate FLOAT,
  installmentValue DECIMAL(10,2),
  createdAt DATE NOT NULL DEFAULT CURRENT_DATE,
  updatedAt DATE NOT NULL DEFAULT CURRENT_DATE,
  FOREIGN KEY (requestId) REFERENCES loanRequests(id)
);

CREATE TABLE loanPayments (
  id SERIAL PRIMARY KEY,
  loanId INT NOT NULL,
  paidValue DECIMAL(10,2) NOT NULL,
  latePenalty DECIMAL(10,2) DEFAULT 0.00,
  dueDate DATE NOT NULL,
  paymentDate DATE,
  status BOOLEAN DEFAULT FALSE,
  createdAt DATE NOT NULL DEFAULT CURRENT_DATE,
  updatedAt DATE NOT NULL DEFAULT CURRENT_DATE,
  FOREIGN KEY (loanId) REFERENCES loans(id)
);
