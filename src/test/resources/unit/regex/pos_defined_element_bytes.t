use strict;
use warnings;
use utf8;
use re 'eval';
use Test::More;

sub read_pos_characters { 0 + $_[0] }
{
    use bytes;
    sub read_pos_bytes { 0 + $_[0] }
}

our (@values, %values);
my $array_error = eval q{ pos @values = 1; 1 };
ok(!$array_error && $@ =~ /^Can't modify array dereference in match position at /,
    'pos rejects an array dereference');

my $hash_error = eval q{ pos %values = 1; 1 };
ok(!$hash_error && $@ =~ /^Can't modify hash dereference in match position at /,
    'pos rejects a hash dereference');

my @array;
sub exercise_array_element {
    $_[0] = 'hello';
    pos($_[0]) = 2;
    is(pos($array[0]), 2, 'array element and argument alias share pos assignment');
    ok($_[0] =~ /\Gl/, 'array element alias supplies pos to a G assertion');
}
exercise_array_element($array[0]);

my %hash;
sub exercise_hash_elements {
    $_[0] = 'hello';
    pos($_[0]) = 3;
    is(pos($hash{block}), 3, 'hash element and argument alias share pos assignment');
    $_[0] =~ /./g;
    is(pos($hash{block}), 4, 'implicit global match updates hash element pos');

    $_[1] = 'hello';
    pos($hash{scalar}) = 3;
    is(pos($_[1]), 3, 'hash element pos is visible through argument alias');
    is(substr($_[1], pos($_[1]), 1), 'l', 'pos reads the aliased element offset');
    $_[1] =~ /(.)/g;
    is($1, 'l', 'global match starts from aliased element pos');

    $_[2] = 'hello';
    () = $_[2] =~ /l/gc;
    is(pos($hash{list}), 4, 'list global match with c updates hash element pos');

    $_[3] = 'hello';
    $_[3] =~ s<e><is(pos($hash{subst}), 1,
        'substitution callback sees aliased element pos')>egg;
    $hash{subst} = 'hello';
    $_[3] =~ /e(?{ is(pos($hash{subst}), 2,
        'regex callback sees aliased element pos') })/;
    pos($hash{subst}) = 1;
    ok($_[3] =~ /\Ge/, 'G assertion uses assigned hash element pos');
}
exercise_hash_elements($hash{block}, $hash{scalar}, $hash{list}, $hash{subst});

my $unicode = "\x{10000}abc";
$unicode =~ /a/g;
is(pos($unicode), 2, 'Unicode scope reports character offset');
{
    use bytes;
    is(read_pos_characters(pos($unicode)), 2,
        'ordinary callee fetches a live pos lvalue as characters');
    is(read_pos_bytes(pos($unicode)), 5,
        'bytes callee fetches a live pos lvalue as bytes');
    my $snapshot = pos($unicode);
    is($snapshot, 5, 'bytes assignment snapshots byte presentation');
    ok($unicode =~ /\Gb/g, 'bytes regex resumes from shared match position');
    my $after = pos($unicode);
    is($after, 6, 'bytes assignment snapshots updated byte presentation');
    pos($unicode) = 5;
}
is(pos($unicode), 5, 'bytes-scope assignment retains its numeric position');
ok(!($unicode =~ /\Gb/g), 'character-unit assignment five does not resume at b');
{
    use bytes;
    pos($unicode) = 2;
}
is(pos($unicode), 2, 'bytes scope stores the assigned numeric position unchanged');
{
    use bytes;
    my $assigned = pos($unicode);
    is($assigned, 2, 'assigned pos remains unchanged in byte scope');
}

done_testing;
