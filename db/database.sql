CREATE TABLE loanRequests (
  id INT PRIMARY KEY,
  customerId INT,
  requestedValue DECIMAL(10,2),
  termInMonths INT,
  status BOOLEAN,
  approvedAt DATE,
  createdAt DATE,
  updatedAt DATE
);

CREATE TABLE loans (
  id INT PRIMARY KEY,
  requestId INT,
  customerId INT,
  approvedValue DECIMAL(10,2),
  interestRate FLOAT,
  installmentValue DECIMAL(10,2),
  status BOOLEAN,
  createdAt DATE,
  updatedAt DATE,
  FOREIGN KEY (requestId) REFERENCES loanRequests(id)
);

CREATE TABLE loanPayments (
  id INT PRIMARY KEY,
  loanId INT,
  paidValue DECIMAL(10,2),
  latePenalty DECIMAL(10,2),
  fee DECIMAL(10,2),
  dueDate DATE,
  paymentDate DATE,
  status BOOLEAN,
  createdAt DATE,
  updatedAt DATE,
  FOREIGN KEY (loanId) REFERENCES loans(id)
);