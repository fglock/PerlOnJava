use Test::More tests => 39;

my $pop = \&CORE::pop;
my $push = \&CORE::push;
my $pos = \&CORE::pos;
my $prototype = \&CORE::prototype;
my $read = \&CORE::read;
my $readline = \&CORE::readline;
my $readpipe = \&CORE::readpipe;
my $ref = \&CORE::ref;
my $recv = \&CORE::recv;
my $reset = \&CORE::reset;
my $select = \&CORE::select;
my $splice = \&CORE::splice;

for my $bad (1, {}, \&CORE::time, \*STDOUT, \my $scalar) {
    eval { $pop->($bad) };
    like($@, qr/^Type of arg 1 to &CORE::pop must be array reference/,
        'CORE::pop rejects non-array references');
}

my @argv = qw(a b c);
local @ARGV = @argv;
is($pop->(), 'c', 'CORE::pop without an argument uses @ARGV at file scope');
is_deeply(\@ARGV, [qw(a b)], 'CORE::pop updates @ARGV');

my @remaining;
my $pop_from_args = sub {
    my $last = $pop->();
    @remaining = @_;
    return $last;
};
is($pop_from_args->(qw(q j k)), 'k', 'CORE::pop without an argument uses caller @_');
is_deeply(\@remaining, [qw(q j)], 'CORE::pop updates caller @_');

my @values;
is($push->(\@values, qw(x y)), 2, 'CORE::push returns the new array size');
is_deeply(\@values, [qw(x y)], 'CORE::push updates the referenced array');

eval { $pos->([], 1) };
like($@, qr/^Too many arguments for match position at /,
    'CORE::pos reports its Perl diagnostic name');
eval { $pos->([]) };
like($@, qr/^Type of arg 1 to &CORE::pos must be scalar reference/,
    'CORE::pos rejects an array reference');

eval { $prototype->(\&CORE::open, 'extra') };
like($@, qr/^Too many arguments for subroutine prototype at /,
    'CORE::prototype reports its Perl diagnostic name');
eval { $readpipe->('', '') };
like($@, qr/^Too many arguments for quoted execution \(.*qx\) at /,
    'CORE::readpipe reports its Perl diagnostic name');
eval { $ref->([], 'extra') };
like($@, qr/^Too many arguments for reference-type operator at /,
    'CORE::ref reports its Perl diagnostic name');
eval { $reset->('a', 'b') };
like($@, qr/^Too many arguments for symbol reset at /,
    'CORE::reset reports its Perl diagnostic name');
eval { $select->(1, 2) };
like($@, qr/^Not enough arguments for select system call at /,
    'CORE::select rejects two-argument system calls');
eval { $select->(1, 2, 3) };
like($@, qr/^Not enough arguments for select system call at /,
    'CORE::select rejects three-argument system calls');
eval { $select->(1, 2, 3, 4, 5) };
like($@, qr/^Too many arguments for select system call at /,
    'CORE::select rejects more than four system-call arguments');

my @spliced = qw(a b c d);
my @removed = $splice->(\@spliced, 1, 2);
is_deeply(\@removed, [qw(b c)], 'CORE::splice returns removed values in list context');
is_deeply(\@spliced, [qw(a d)], 'CORE::splice mutates the array in list context');
@spliced = qw(a b c d e);
@removed = $splice->(\@spliced, 1, 1, qw(x y));
is_deeply(\@removed, ['b'], 'CORE::splice returns removed values with replacement');
is_deeply(\@spliced, [qw(a x y c d e)], 'CORE::splice applies list replacement');

my $old_selected = $select->();
my $new_selected;
is($select->($new_selected), $old_selected, 'CORE::select returns the previous handle');
is(lc(ref($new_selected)), 'glob', 'CORE::select autovivifies an undefined handle');
is($select->(), $new_selected, 'CORE::select installs the autovivified handle');
$select->($old_selected);

my $source = 'abcdef';
open my $fh, '<', \$source or die $!;
my $buffer = '';
is($read->($fh, \$buffer, 3), 3, 'CORE::read accepts a scalar reference');
is($buffer, 'abc', 'CORE::read stores bytes through its scalar reference');
for my $bad ([], 1, bless([], 'ScalarOverload')) {
    eval { $read->($fh, $bad, 1) };
    like($@, qr/^Type of arg 2 to &CORE::read must be scalar reference/,
        'CORE::read rejects a non-scalar reference');
}
for my $bad ([], 1, bless([], 'ScalarOverload')) {
    eval { $recv->(undef, $bad, 1, 0) };
    like($@, qr/^Type of arg 2 to &CORE::recv must be scalar reference/,
        'CORE::recv rejects a non-scalar reference');
}

{
    local *ARGV = *DATA;
    my $data_start = tell DATA;
    my $read_from_argv = sub { $readline->() };
    my $first_line = $read_from_argv->();
    is($first_line, "alpha\n", 'CORE::readline uses ARGV in scalar context');
    seek DATA, $data_start, 0;
    my @lines = $read_from_argv->();
    is_deeply(\@lines, ["alpha\n", "beta\n"],
        'CORE::readline uses ARGV and preserves list context');
}

my $once = sub { "a" =~ m?a? };
$once->();
$reset->();
ok($once->(), 'CORE::reset clears match-once state');
{
    package CoreResetRegression;
    our ($b, $banana, $keep) = ('b', 'x', 'y');
    $reset->('b');
    main::is_deeply([$b, $banana, $keep], [undef, undef, 'y'],
        'CORE::reset clears matching package globals');
}

__DATA__
alpha
beta
