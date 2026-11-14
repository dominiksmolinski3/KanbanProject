package pl.myproject.kanbanproject2.board;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BoardListRolesTest {

    @Test
    @DisplayName("listing boards reads their roles in one query, however many boards there are")
    void theListAsksForRolesOnce() {
        var owner = TenancyFixtures.user(1);
        var viewer = TenancyFixtures.user(2);
        var first = TenancyFixtures.board(10, owner);
        var second = TenancyFixtures.board(11, owner);
        var third = TenancyFixtures.board(12, owner);
        second.addMember(viewer);
        var repository = mock(BoardRepository.class);
        when(repository.findMemberRolesIn(List.of(10, 11, 12)))
                .thenReturn(List.<Object[]>of(new Object[] {11, 2, BoardRole.VIEWER.name()}));

        var listed = new BoardMapper(repository).applyAll(List.of(first, second, third), viewer);

        assertThat(listed).extracting(BoardDto::id).containsExactly(10, 11, 12);
        assertThat(listed.get(1).role()).isEqualTo(BoardRole.VIEWER);
        assertThat(listed.get(0).role()).isEqualTo(BoardRole.MEMBER);
        verify(repository, times(1)).findMemberRolesIn(anyCollection());
        verify(repository, never()).findMemberRoles(any());
    }

    @Test
    @DisplayName("a board says which of its members have an avatar")
    void membersSayWhetherTheyHaveAnAvatar() {
        var owner = TenancyFixtures.user(1);
        var member = TenancyFixtures.user(2);
        member.setAvatarBlobName("avatars/2/3f2c");
        var board = TenancyFixtures.board(10, owner);
        board.addMember(member);
        var repository = mock(BoardRepository.class);

        var listed = new BoardMapper(repository).applyAll(List.of(board), owner);

        assertThat(listed.getFirst().members()).extracting(BoardMemberDto::id, BoardMemberDto::hasAvatar)
                .containsExactly(tuple(1, false), tuple(2, true));
    }

    @Test
    @DisplayName("an empty list asks nothing")
    void anEmptyListAsksNothing() {
        var repository = mock(BoardRepository.class);

        assertThat(new BoardMapper(repository).applyAll(List.of(), TenancyFixtures.user(1))).isEmpty();

        verify(repository, never()).findMemberRolesIn(anyCollection());
    }
}
