use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More;

# Model Mojo::DOM::HTML's strong child arrays and weak parent links while it
# closes nested optional <li> tags and then starts the next outer list item.
sub close_tag {
    my ($current, $tag) = @_;
    my $next = $$current;
    while ($next && $next->[0] ne 'root') {
        if ($next->[1] eq $tag) {
            $$current = $next->[3];
            return;
        }
        $next = $next->[3];
    }
}

sub start_tag {
    my ($current, $tag) = @_;
    if ($tag eq 'li' && $$current->[0] ne 'root') {
        my $parent = $$current;
        while ($parent->[0] ne 'root' && $parent->[1] ne 'ul' && $parent->[1] ne 'ol') {
            close_tag($current, 'li') if $parent->[1] eq 'li';
            $parent = $parent->[3];
            last unless ref $parent;
        }
    }
    push @$$current, my $new = ['tag', $tag, {}, $$current];
    weaken $new->[3];
    $$current = $new;
}

sub text_node {
    my ($current, $text) = @_;
    push @$$current, my $new = ['text', $text, $$current];
    weaken $new->[2];
}

sub parse_list {
    my $current = my $tree = ['root'];
    start_tag(\$current, 'ul');
    text_node(\$current, "\n  ");
    start_tag(\$current, 'li');
    text_node(\$current, "\n    ");
    start_tag(\$current, 'ol');
    text_node(\$current, "\n      ");
    start_tag(\$current, 'li');
    text_node(\$current, "F\n      ");
    start_tag(\$current, 'li');
    text_node(\$current, "G\n    ");
    close_tag(\$current, 'ol');
    start_tag(\$current, 'li');
    return ($tree, $current);
}

my ($tree, $current) = parse_list();
ok(defined $current->[3], 'new outer list item retains its parent');
is($current->[3][1] // 'undef', 'ul', 'new outer list item points to the ul');
ok(isweak($current->[3]), 'parent link remains weak');
is($tree->[1][1], 'ul', 'the root retains the list tree');

done_testing;
