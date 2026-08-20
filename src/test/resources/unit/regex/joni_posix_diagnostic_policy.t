use strict;
use warnings;
use utf8;
use Test::More;

sub compile_pattern {
    my ($pattern, $strict, $mods, $suppress_regexp) = @_;
    $mods //= '';
    my @warnings;
    my ($regex, $error);
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        my $source = q{no warnings 'experimental::regex_sets'; }
            . ($strict
                ? q{no warnings 'experimental::re_strict'; use re 'strict'; }
                : '')
            . ($suppress_regexp ? q{no warnings 'regexp'; } : '')
            . 'qr/' . $pattern . '/' . $mods;
        $regex = eval $source;
        $error = $@;
    }
    return ($regex, $error, \@warnings);
}

my @cases = (
    [ 'outside_valid',      '[:alpha:]',
      [ 'POSIX syntax [: :] belongs inside character classes' ] ],
    [ 'outside_invalid',    '[:zog:]',
      [ q{POSIX syntax [: :] belongs inside character classes (but this one isn't fully valid)} ] ],
    [ 'outside_incomplete', '[:blank]',
      [ q{POSIX syntax [: :] belongs inside character classes (but this one isn't fully valid)} ] ],
    [ 'outside_collating',  '[.zog.]',
      [ q{POSIX syntax [. .] belongs inside character classes (but this one isn't implemented)} ] ],
    [ 'adjacent_outside',   '[[:cntrl:]][:^ascii:]',
      [ 'POSIX syntax [: :] belongs inside character classes' ] ],
    [ 'missing_colon',      '[[:digit]]',
      [ q{Assuming NOT a POSIX class since there is no terminating ':'} ] ],
    [ 'missing_outer',      '[[:digit:foo]',
      [ q{Assuming NOT a POSIX class since there is no terminating ']'} ] ],
    [ 'near_name',          '[[:dgit]]',
      [ q{Assuming NOT a POSIX class since there is no terminating ':'} ] ],
    [ 'uppercase',          '[[:DIGIT]]',
      [ 'Assuming NOT a POSIX class since the name must be all lowercase letters',
        q{Assuming NOT a POSIX class since there is no terminating ':'} ] ],
    [ 'no_start_colon',     '[[digit]',
      [ q{Assuming NOT a POSIX class since there must be a starting ':'},
        q{Assuming NOT a POSIX class since there is no terminating ':'} ] ],
    [ 'caret_before_colon', '[[^word]',
      [ q{Assuming NOT a POSIX class since the '^' must come after the colon},
        q{Assuming NOT a POSIX class since there must be a starting ':'},
        q{Assuming NOT a POSIX class since there is no terminating ':'} ] ],
    [ 'missing_open',       '[foo:lower:]]',
      [ q{Assuming NOT a POSIX class since it doesn't start with a '['} ] ],
    [ 'semicolon',          '[[;upper;]]',
      [ 'Assuming NOT a POSIX class since a semi-colon was found instead of a colon',
        'Assuming NOT a POSIX class since a semi-colon was found instead of a colon' ] ],
    [ 'missing_open_semis', '[foo;punct;]]',
      [ q{Assuming NOT a POSIX class since it doesn't start with a '['},
        'Assuming NOT a POSIX class since a semi-colon was found instead of a colon',
        'Assuming NOT a POSIX class since a semi-colon was found instead of a colon' ] ],
);

for my $strict (0, 1) {
    for my $case (@cases) {
        my ($name, $pattern, $expected) = @$case;
        my ($regex, $error, $warnings) = compile_pattern($pattern, $strict);
        ok(defined($regex), "$name compiles" . ($strict ? ' under strict' : ''));
        is($error, '', "$name is nonfatal" . ($strict ? ' under strict' : ''));
        is(scalar(@$warnings), scalar(@$expected),
            "$name warning count" . ($strict ? ' under strict' : ''));
        for my $index (0 .. $#$expected) {
            like($warnings->[$index], qr/^\Q$expected->[$index]\E in regex; marked by <-- HERE/,
                "$name warning $index preserves order and positioned rendering");
        }
    }
}

for my $case (
    [ 'far_name'       => '[[:dgt]]' ],
    [ 'extended_short' => '(?[[:w:]])' ],
    [ 'wide_standard'  => "\x{30cd}[[:\x{30cd}:]]\x{30cd}" ],
    [ 'wide_extended'  => "\x{30cd}(?[[:\x{30cd}:]])\x{30cd}" ],
    [ 'valid_standard' => '[[:digit:]]' ],
    [ 'valid_extended' => '(?[[:digit:]])' ],
) {
    my ($name, $pattern) = @$case;
    my ($regex, $error, $warnings) = compile_pattern($pattern, 0);
    ok(defined($regex), "$name valid control compiles");
    is($error, '', "$name valid control is nonfatal");
    is(scalar(@$warnings), 0, "$name valid control is warning-free");
}

{
    my ($regex, $error, $warnings) = compile_pattern('[:alpha:]', 0, '', 1);
    ok(defined($regex), 'regexp warning suppression keeps malformed POSIX syntax compilable');
    is($error, '', 'regexp warning suppression stays nonfatal');
    is(scalar(@$warnings), 0, 'regexp warning suppression is honored');
}

done_testing;
