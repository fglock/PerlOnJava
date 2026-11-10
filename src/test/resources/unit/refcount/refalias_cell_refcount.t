use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use B qw(svref_2object);
use Scalar::Util qw(refaddr);
use Test::More tests => 6;

sub refcount { svref_2object($_[0])->REFCNT }

my $object = bless {}, 'Local::RefaliasCellRefcount';
my $source = $object;
my $source_cell = \$source;
my $cell_count_before = refcount($source_cell);
my $referent_count_before = refcount($source);

{
    my $alias;
    \$alias = $source_cell;

    is(refaddr(\$alias), refaddr($source_cell),
        'refalias names the same scalar cell');
    is(refcount($source_cell), $cell_count_before + 1,
        'refalias increments the scalar cell refcount');
    is(refcount($source), $referent_count_before,
        'refalias does not add another owner of the cell contents');
}

is(refcount($source_cell), $cell_count_before,
    'leaving the alias scope releases the scalar cell refcount');

my $cell_count_before_eval = refcount($source_cell);
eval {
    my $alias;
    \$alias = $source_cell;
    die "leave eval scope\n";
};
ok($@, 'eval catches the scope-exit exception');
is(refcount($source_cell), $cell_count_before_eval,
    'eval exception cleanup releases the scalar cell refcount');

done_testing;
