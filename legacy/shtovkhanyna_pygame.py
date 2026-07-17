# Оригінальний прототип гри «Штовханина» (pygame), збережений для історії.
# Original pygame prototype of the game, kept for reference.
# The Android app in ../app is a full rewrite of this idea.

import pygame
import sys
from typing import List, Tuple, Optional

# Константи
WINDOW_SIZE = 800
BOARD_SIZE = 5
CELL_SIZE = 100
BOARD_OFFSET = 150

COLORS = {
    'bg': (245, 245, 220),
    'board': (139, 90, 43),
    'cell': (210, 180, 140),
    'grid': (101, 67, 33),
    'player1': (220, 20, 60),
    'player2': (30, 144, 255),
    'highlight': (255, 215, 0),
    'text': (50, 50, 50),
    'button': (100, 200, 100),
    'button_hover': (120, 220, 120),
    'disabled': (150, 150, 150)
}

class Piece:
    def __init__(self, player: int, row: int, col: int):
        self.player = player
        self.row = row
        self.col = col

class SpecialAbility:
    def __init__(self, name: str, cooldown: int):
        self.name = name
        self.cooldown = cooldown
        self.current_cooldown = 0

    def is_available(self) -> bool:
        return self.current_cooldown == 0

    def use(self):
        self.current_cooldown = self.cooldown

    def tick(self):
        if self.current_cooldown > 0:
            self.current_cooldown -= 1

class Button:
    def __init__(self, x, y, width, height, text, action):
        self.rect = pygame.Rect(x, y, width, height)
        self.text = text
        self.action = action
        self.enabled = True
        self.hovered = False

    def draw(self, screen, font):
        color = COLORS['disabled'] if not self.enabled else (COLORS['button_hover'] if self.hovered else COLORS['button'])
        pygame.draw.rect(screen, color, self.rect, border_radius=10)
        pygame.draw.rect(screen, COLORS['grid'], self.rect, 3, border_radius=10)

        text_surf = font.render(self.text, True, COLORS['text'])
        text_rect = text_surf.get_rect(center=self.rect.center)
        screen.blit(text_surf, text_rect)

    def handle_event(self, event):
        if event.type == pygame.MOUSEMOTION:
            self.hovered = self.rect.collidepoint(event.pos)
        elif event.type == pygame.MOUSEBUTTONDOWN and self.enabled:
            if self.rect.collidepoint(event.pos):
                self.action()
                return True
        return False

class Game:
    def __init__(self, win_condition: str = "one"):
        pygame.init()
        self.screen = pygame.display.set_mode((WINDOW_SIZE, WINDOW_SIZE))
        pygame.display.set_caption("Штовханина")
        self.clock = pygame.time.Clock()
        self.font = pygame.font.Font(None, 32)
        self.small_font = pygame.font.Font(None, 24)

        self.board = [[None for _ in range(5)] for _ in range(5)]
        self.current_player = 1
        self.pieces = {1: [], 2: []}
        self.setup_phase = True
        self.pieces_to_place = {1: 2, 2: 2}
        self.win_condition = win_condition

        self.selected_cell = None
        self.first_action_done = False
        self.moved_piece = None
        self.message = "Гравець 1: розставте фішки"

        self.abilities = {
            1: [
                SpecialAbility("Переміщення", 2),
                SpecialAbility("Обмін", 3),
                SpecialAbility("Зсув", 3)
            ],
            2: [
                SpecialAbility("Переміщення", 2),
                SpecialAbility("Обмін", 3),
                SpecialAbility("Зсув", 3)
            ]
        }

        self.special_mode = None
        self.special_selection = []
        self.winner = None

        self.buttons = []
        self.create_ability_buttons()

    def create_ability_buttons(self):
        self.buttons = []
        y_start = 600
        for i in range(3):
            btn = Button(50, y_start + i * 60, 200, 50,
                        self.abilities[self.current_player][i].name,
                        lambda idx=i: self.activate_special(idx))
            self.buttons.append(btn)

    def update_buttons(self):
        for i, btn in enumerate(self.buttons):
            ability = self.abilities[self.current_player][i]
            btn.enabled = ability.is_available() and not self.setup_phase and self.first_action_done
            cooldown = f" ({ability.current_cooldown})" if ability.current_cooldown > 0 else ""
            btn.text = f"{ability.name}{cooldown}"

    def get_cell_from_pos(self, pos: Tuple[int, int]) -> Optional[Tuple[int, int]]:
        x, y = pos
        if x < BOARD_OFFSET or y < BOARD_OFFSET:
            return None

        col = (x - BOARD_OFFSET) // CELL_SIZE
        row = (y - BOARD_OFFSET) // CELL_SIZE

        if 0 <= row < 5 and 0 <= col < 5:
            return (row, col)
        return None

    def is_on_edge(self, row: int, col: int) -> bool:
        return row == 0 or row == 4 or col == 0 or col == 4

    def is_center(self, row: int, col: int) -> bool:
        return row == 2 and col == 2

    def has_opposite_piece(self, row: int, col: int, player: int) -> bool:
        opponent = 3 - player
        for c in range(5):
            if c != col and self.board[row][c] and self.board[row][c].player == opponent:
                return True
        for r in range(5):
            if r != row and self.board[r][col] and self.board[r][col].player == opponent:
                return True
        return False

    def place_piece(self, row: int, col: int) -> bool:
        if self.board[row][col] is not None:
            self.message = "Клітинка вже зайнята!"
            return False

        if self.is_center(row, col):
            self.message = "Центр має бути порожнім!"
            return False

        if self.is_on_edge(row, col):
            self.message = "Не можна на край!"
            return False

        if self.has_opposite_piece(row, col, self.current_player):
            self.message = "Не напроти суперника!"
            return False

        piece = Piece(self.current_player, row, col)
        self.board[row][col] = piece
        self.pieces[self.current_player].append(piece)
        self.pieces_to_place[self.current_player] -= 1

        if all(count == 0 for count in self.pieces_to_place.values()):
            self.setup_phase = False
            self.current_player = 1
            self.message = "Гравець 1: зробіть перший рух"
        else:
            self.current_player = 3 - self.current_player
            self.message = f"Гравець {self.current_player}: розставте фішки"

        return True

    def push_piece(self, piece: Piece, dr: int, dc: int) -> bool:
        new_row = piece.row + dr
        new_col = piece.col + dc

        if new_row < 0 or new_row >= 5 or new_col < 0 or new_col >= 5:
            self.board[piece.row][piece.col] = None
            self.pieces[piece.player].remove(piece)
            self.message = f"Фішка гравця {piece.player} вилетіла!"
            return True

        if self.board[new_row][new_col]:
            knocked = self.board[new_row][new_col]
            if self.push_piece(knocked, dr, dc):
                return True

        self.board[piece.row][piece.col] = None
        piece.row = new_row
        piece.col = new_col
        self.board[new_row][new_col] = piece

        return False

    def move_piece(self, from_row: int, from_col: int, to_row: int, to_col: int) -> bool:
        piece = self.board[from_row][from_col]

        if not piece:
            self.message = "Тут немає фішки!"
            return False

        if piece.player != self.current_player:
            self.message = "Це не ваша фішка!"
            return False

        if self.first_action_done and piece == self.moved_piece:
            self.message = "Фішка вже рухалась!"
            return False

        dr = to_row - from_row
        dc = to_col - from_col

        if abs(dr) + abs(dc) != 1:
            self.message = "Рух тільки на 1 клітинку!"
            return False

        if self.board[to_row][to_col]:
            target = self.board[to_row][to_col]
            self.push_piece(target, dr, dc)

        self.board[from_row][from_col] = None
        piece.row = to_row
        piece.col = to_col
        self.board[to_row][to_col] = piece

        if not self.first_action_done:
            self.moved_piece = piece
            self.first_action_done = True
            self.message = f"Гравець {self.current_player}: друга дія"
        else:
            self.end_turn()

        return True

    def check_win(self) -> Optional[int]:
        for player in [1, 2]:
            pieces_left = len(self.pieces[player])
            if self.win_condition == "one" and pieces_left < 2:
                return 3 - player
            elif self.win_condition == "both" and pieces_left == 0:
                return 3 - player
        return None

    def end_turn(self):
        for ability in self.abilities[self.current_player]:
            ability.tick()

        self.current_player = 3 - self.current_player
        self.first_action_done = False
        self.moved_piece = None
        self.selected_cell = None
        self.message = f"Гравець {self.current_player}: перший рух"
        self.update_buttons()

    def activate_special(self, ability_idx: int):
        if not self.first_action_done:
            self.message = "Спочатку зробіть перший рух!"
            return

        ability = self.abilities[self.current_player][ability_idx]
        if not ability.is_available():
            return

        if ability_idx == 0:
            self.special_mode = "move"
            self.message = "Оберіть фішку для переміщення"
        elif ability_idx == 1:
            self.special_mode = "swap"
            self.special_selection = []
            self.message = "Оберіть 2 фішки для обміну"
        elif ability_idx == 2:
            self.special_mode = "shift"
            self.message = "Клік на край ряду/колонки для зсуву"

    def handle_special_move(self, row: int, col: int):
        if not self.selected_cell:
            if self.board[row][col]:
                self.selected_cell = (row, col)
                self.message = "Куди перемістити?"
        else:
            from_row, from_col = self.selected_cell
            if self.move_piece(from_row, from_col, row, col):
                self.abilities[self.current_player][0].use()
                self.special_mode = None
                self.selected_cell = None
                self.end_turn()

    def handle_special_swap(self, row: int, col: int):
        if self.board[row][col]:
            self.special_selection.append((row, col))

            if len(self.special_selection) == 2:
                r1, c1 = self.special_selection[0]
                r2, c2 = self.special_selection[1]

                p1 = self.board[r1][c1]
                p2 = self.board[r2][c2]

                self.board[r1][c1], self.board[r2][c2] = p2, p1
                p1.row, p1.col = r2, c2
                p2.row, p2.col = r1, c1

                self.abilities[self.current_player][1].use()
                self.special_mode = None
                self.special_selection = []
                self.message = "Фішки обміняні!"
                self.end_turn()

    def handle_special_shift(self, x: int, y: int):
        # Визначаємо чи клік на краю
        if x < BOARD_OFFSET - 20 or x > BOARD_OFFSET + BOARD_SIZE * CELL_SIZE + 20:
            return
        if y < BOARD_OFFSET - 20 or y > BOARD_OFFSET + BOARD_SIZE * CELL_SIZE + 20:
            return

        # Зсув колонки
        if BOARD_OFFSET <= x < BOARD_OFFSET + BOARD_SIZE * CELL_SIZE:
            col = (x - BOARD_OFFSET) // CELL_SIZE
            if y < BOARD_OFFSET:
                self.shift_col(col, -1)
                self.abilities[self.current_player][2].use()
                self.special_mode = None
                self.end_turn()
            elif y >= BOARD_OFFSET + BOARD_SIZE * CELL_SIZE:
                self.shift_col(col, 1)
                self.abilities[self.current_player][2].use()
                self.special_mode = None
                self.end_turn()

        # Зсув ряду
        if BOARD_OFFSET <= y < BOARD_OFFSET + BOARD_SIZE * CELL_SIZE:
            row = (y - BOARD_OFFSET) // CELL_SIZE
            if x < BOARD_OFFSET:
                self.shift_row(row, -1)
                self.abilities[self.current_player][2].use()
                self.special_mode = None
                self.end_turn()
            elif x >= BOARD_OFFSET + BOARD_SIZE * CELL_SIZE:
                self.shift_row(row, 1)
                self.abilities[self.current_player][2].use()
                self.special_mode = None
                self.end_turn()

    def shift_row(self, row: int, direction: int):
        pieces_in_row = []
        for col in range(5):
            if self.board[row][col]:
                pieces_in_row.append((self.board[row][col], col))

        for col in range(5):
            self.board[row][col] = None

        for piece, old_col in pieces_in_row:
            new_col = old_col + direction
            if 0 <= new_col < 5:
                piece.col = new_col
                self.board[row][new_col] = piece

    def shift_col(self, col: int, direction: int):
        pieces_in_col = []
        for row in range(5):
            if self.board[row][col]:
                pieces_in_col.append((self.board[row][col], row))

        for row in range(5):
            self.board[row][col] = None

        for piece, old_row in pieces_in_col:
            new_row = old_row + direction
            if 0 <= new_row < 5:
                piece.row = new_row
                self.board[new_row][col] = piece

    def handle_click(self, pos: Tuple[int, int]):
        if self.winner:
            return

        cell = self.get_cell_from_pos(pos)

        if self.special_mode == "move" and cell:
            self.handle_special_move(*cell)
            return
        elif self.special_mode == "swap" and cell:
            self.handle_special_swap(*cell)
            return
        elif self.special_mode == "shift":
            self.handle_special_shift(*pos)
            return

        if not cell:
            return

        row, col = cell

        if self.setup_phase:
            self.place_piece(row, col)
        else:
            if not self.selected_cell:
                if self.board[row][col] and self.board[row][col].player == self.current_player:
                    self.selected_cell = (row, col)
            else:
                from_row, from_col = self.selected_cell
                if (row, col) == self.selected_cell:
                    self.selected_cell = None
                else:
                    self.move_piece(from_row, from_col, row, col)
                    winner = self.check_win()
                    if winner:
                        self.winner = winner
                        self.message = f"🎉 ПЕРЕМІГ ГРАВЕЦЬ {winner}! 🎉"

    def draw(self):
        self.screen.fill(COLORS['bg'])

        # Дошка
        board_rect = pygame.Rect(BOARD_OFFSET, BOARD_OFFSET,
                                BOARD_SIZE * CELL_SIZE, BOARD_SIZE * CELL_SIZE)
        pygame.draw.rect(self.screen, COLORS['board'], board_rect)

        # Стрілки для зсуву (якщо активний режим)
        if self.special_mode == "shift":
            # Стрілки для колонок
            for col in range(5):
                x = BOARD_OFFSET + col * CELL_SIZE + CELL_SIZE // 2
                # Вгору
                pygame.draw.polygon(self.screen, COLORS['highlight'],
                                  [(x, BOARD_OFFSET - 30),
                                   (x - 15, BOARD_OFFSET - 10),
                                   (x + 15, BOARD_OFFSET - 10)])
                # Вниз
                y_down = BOARD_OFFSET + BOARD_SIZE * CELL_SIZE
                pygame.draw.polygon(self.screen, COLORS['highlight'],
                                  [(x, y_down + 30),
                                   (x - 15, y_down + 10),
                                   (x + 15, y_down + 10)])

            # Стрілки для рядів
            for row in range(5):
                y = BOARD_OFFSET + row * CELL_SIZE + CELL_SIZE // 2
                # Вліво
                pygame.draw.polygon(self.screen, COLORS['highlight'],
                                  [(BOARD_OFFSET - 30, y),
                                   (BOARD_OFFSET - 10, y - 15),
                                   (BOARD_OFFSET - 10, y + 15)])
                # Вправо
                x_right = BOARD_OFFSET + BOARD_SIZE * CELL_SIZE
                pygame.draw.polygon(self.screen, COLORS['highlight'],
                                  [(x_right + 30, y),
                                   (x_right + 10, y - 15),
                                   (x_right + 10, y + 15)])

        # Клітинки
        for row in range(5):
            for col in range(5):
                x = BOARD_OFFSET + col * CELL_SIZE
                y = BOARD_OFFSET + row * CELL_SIZE

                cell_rect = pygame.Rect(x, y, CELL_SIZE, CELL_SIZE)

                # Підсвітка
                if self.selected_cell == (row, col):
                    pygame.draw.rect(self.screen, COLORS['highlight'], cell_rect)
                elif (row, col) in self.special_selection:
                    pygame.draw.rect(self.screen, COLORS['highlight'], cell_rect)
                else:
                    pygame.draw.rect(self.screen, COLORS['cell'], cell_rect)

                pygame.draw.rect(self.screen, COLORS['grid'], cell_rect, 2)

                # Центр
                if self.is_center(row, col):
                    pygame.draw.line(self.screen, COLORS['grid'],
                                   (x + 20, y + 20), (x + 80, y + 80), 3)
                    pygame.draw.line(self.screen, COLORS['grid'],
                                   (x + 80, y + 20), (x + 20, y + 80), 3)

                # Фішки
                piece = self.board[row][col]
                if piece:
                    center = (x + CELL_SIZE // 2, y + CELL_SIZE // 2)
                    color = COLORS['player1'] if piece.player == 1 else COLORS['player2']
                    pygame.draw.circle(self.screen, color, center, 35)
                    pygame.draw.circle(self.screen, COLORS['grid'], center, 35, 3)

                    # Номер гравця
                    text = self.font.render(str(piece.player), True, (255, 255, 255))
                    text_rect = text.get_rect(center=center)
                    self.screen.blit(text, text_rect)

        # Повідомлення
        msg_surf = self.font.render(self.message, True, COLORS['text'])
        self.screen.blit(msg_surf, (50, 50))

        # Кнопки здібностей
        if not self.setup_phase:
            for btn in self.buttons:
                btn.draw(self.screen, self.small_font)

        # Легенда
        legend_y = 100
        p1_circle = pygame.draw.circle(self.screen, COLORS['player1'], (50, legend_y), 15)
        p1_text = self.small_font.render("Гравець 1", True, COLORS['text'])
        self.screen.blit(p1_text, (75, legend_y - 10))

        p2_circle = pygame.draw.circle(self.screen, COLORS['player2'], (50, legend_y + 30), 15)
        p2_text = self.small_font.render("Гравець 2", True, COLORS['text'])
        self.screen.blit(p2_text, (75, legend_y + 20))

        pygame.display.flip()

    def run(self):
        running = True
        while running:
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    running = False
                elif event.type == pygame.MOUSEBUTTONDOWN:
                    # Спочатку перевіряємо кнопки
                    button_clicked = False
                    for btn in self.buttons:
                        if btn.handle_event(event):
                            button_clicked = True
                            break

                    # Якщо кнопка не натиснута, обробляємо клік по дошці
                    if not button_clicked:
                        self.handle_click(event.pos)
                elif event.type == pygame.MOUSEMOTION:
                    for btn in self.buttons:
                        btn.handle_event(event)

            self.draw()
            self.clock.tick(60)

        pygame.quit()
        sys.exit()

# Меню вибору режиму
def main():
    pygame.init()
    screen = pygame.display.set_mode((400, 300))
    pygame.display.set_caption("Штовханина - Налаштування")
    font = pygame.font.Font(None, 32)
    small_font = pygame.font.Font(None, 24)

    win_condition = None

    title = font.render("Умова перемоги:", True, COLORS['text'])

    btn1 = Button(50, 100, 300, 60, "1 фішка вилетіла", lambda: None)
    btn2 = Button(50, 180, 300, 60, "Обидві фішки вилетіли", lambda: None)

    running = True
    while running:
        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit()
                sys.exit()
            elif event.type == pygame.MOUSEBUTTONDOWN:
                if btn1.rect.collidepoint(event.pos):
                    win_condition = "one"
                    running = False
                elif btn2.rect.collidepoint(event.pos):
                    win_condition = "both"
                    running = False
            elif event.type == pygame.MOUSEMOTION:
                btn1.hovered = btn1.rect.collidepoint(event.pos)
                btn2.hovered = btn2.rect.collidepoint(event.pos)

        screen.fill(COLORS['bg'])
        screen.blit(title, (50, 30))
        btn1.draw(screen, small_font)
        btn2.draw(screen, small_font)
        pygame.display.flip()

    pygame.quit()

    if win_condition:
        game = Game(win_condition)
        game.run()

if __name__ == "__main__":
    main()
