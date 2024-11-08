use strict;
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
print "not " if $@; say "ok # carp warning";

# Test croak (die from the perspective of the caller)
eval {
    croak "This is a croak error";
};
print "not " unless $@ =~ /This is a croak error/; say "ok # croak error";

# Test confess (die with stack backtrace)
eval {
    confess "This is a confess error";
};
print "not " unless $@ =~ /This is a confess error/; say "ok # confess error with backtrace";

# Test cluck (warn with stack backtrace)
eval {
    cluck "This is a cluck warning";
};
print "not " if $@; say "ok # cluck warning with backtrace";

# Test longmess (generate a long stack trace message)
my $long_message = longmess("This is a longmess message");
print "not " unless $long_message =~ /This is a longmess message/; say "ok # longmess message";

# Test shortmess (generate a short stack trace message)
my $short_message = shortmess("This is a shortmess message");
print "not " unless $short_message =~ /This is a shortmess message/; say "ok # shortmess message";

