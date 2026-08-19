use strict;
use warnings;
use Test::More;

sub compile_pattern {
    my ($pattern, $strict) = @_;
    my (@warnings, $regex, $error);
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        if ($strict) {
            no warnings 'experimental::re_strict';
            use re 'strict';
            $regex = eval { qr/$pattern/ };
        }
        else {
            $regex = eval { qr/$pattern/ };
        }
        $error = $@;
    }
    return ($regex, $error, \@warnings);
}

for my $case (
    [ 'a{,2}',          'aa' ],
    [ 'a{, 2 }',        'aa' ],
    [ 'a{ , 2 }',       'aa' ],
    [ '[x]{, 2}',       'xx' ],
    [ '\p{Latin}{ , 2 }', 'a' ],
) {
    my ($pattern, $subject) = @$case;
    for my $strict (0, 1) {
        my ($regex, $error, $warnings) = compile_pattern($pattern, $strict);
        ok(defined($regex) && $error eq '' && !@$warnings && $subject =~ /\A$regex\z/,
            "$pattern is a quiet quantifier" . ($strict ? ' under re strict' : ''));
    }
}

done_testing;
