use strict;
use warnings;
use Test::More;

my $loaded = eval { require Mojo::DOM::HTML; 1 };
plan skip_all => 'Mojolicious is not installed' unless $loaded;

my @warnings;
my $dom;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $dom = Mojo::DOM::HTML->new;
    $dom->parse(
        '<html><head><title>Generated page</title></head>'
          . '<body><nav>Suggestion</nav></body></html>'
    );
}

is_deeply(\@warnings, [], 'incremental HTML parsing does not lose weak parent nodes');
my $tree = $dom->tree;
is($tree->[1][1], 'html', 'root retains the html node');
is($tree->[1][4][1], 'head', 'html retains the head node');
is($tree->[1][4][4][1], 'title', 'head retains the title node');
is($tree->[1][4][4][3], $tree->[1][4], 'title weak parent points to head');
is($tree->[1][5][1], 'body', 'html retains the body node');
is($tree->[1][5][4][1], 'nav', 'body retains the navigation node');

done_testing;
