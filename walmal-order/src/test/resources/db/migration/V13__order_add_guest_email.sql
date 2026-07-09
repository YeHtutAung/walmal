-- Allow guest orders: user_id becomes nullable, guest_email added
ALTER TABLE order_orders
    ALTER COLUMN user_id DROP NOT NULL,
    ADD COLUMN guest_email VARCHAR(320);

-- Partial index for guest orders (faster lookup by guest email)
CREATE INDEX idx_ord_orders_guest_email ON order_orders (guest_email) WHERE guest_email IS NOT NULL;
