use strict;
use Test::More;
use warnings;
use Carp qw( carp cluck croak confess longmess shortmess );
use feature 'say';

sub printable {
    my $str = join("", @_);
    $str =~ s/\n/\n# /g;
    return "# ${str}\n"
};

$SIG{__WARN__} = sub {
    print printable("Warning: ", @_);
};

# Test carp (warn from the perspective of the caller)
eval {
    carp "This is a carp warning";
};
ok(!($@), 'carp warning');

# Test croak (die from the perspective of the caller)
eval {
    croak "This is a croak error";
};
ok($@ =~ /This is a croak error/, 'croak error');

# Test confess (die with stack backtrace)
eval {
    confess "This is a confess error";
};
ok($@ =~ /This is a confess error/, 'confess error with backtrace');

# Test cluck (warn with stack backtrace)
eval {
    cluck "This is a cluck warning";
};
ok(!($@), 'cluck warning with backtrace');

# Test longmess (generate a long stack trace message)
my $long_message = longmess("This is a longmess message");
ok($long_message =~ /This is a longmess message/, 'longmess message');

# Test shortmess (generate a short stack trace message)
my $short_message = shortmess("This is a shortmess message");
ok($short_message =~ /This is a shortmess message/, 'shortmess message');

done_testing();
