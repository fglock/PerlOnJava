use strict;
use warnings;
use threads;
use threads::shared;

print "1..4\n";

{
    package SharedScopeExitCookie;

    sub new {
        my ($class, $flavour) = @_;
        my $self = threads::shared::shared_clone({ flavour => $flavour });
        return bless($self, $class);
    }

    sub DESTROY {
        delete $_[0]{flavour};
    }
}

my @jar :shared;

sub store_cookie {
    my ($cookie) = @_;
    push @jar, $cookie;
    return $jar[-1];
}

my $parent_cookie = SharedScopeExitCookie->new('parent');
store_cookie($parent_cookie);
print $jar[-1]{flavour} eq 'parent'
    ? "ok 1 - shared array retains parent object\n"
    : "not ok 1 - shared array retains parent object\n";

my $child = threads->create(sub {
    my $cookie = SharedScopeExitCookie->new('child');
    return store_cookie($cookie)->{flavour};
});
print $child->join eq 'child'
    ? "ok 2 - child can store a shared object\n"
    : "not ok 2 - child can store a shared object\n";

print $jar[-1]{flavour} eq 'child'
    ? "ok 3 - child scope exit does not destroy canonical array object\n"
    : "not ok 3 - child scope exit does not destroy canonical array object\n";

my $fetched = pop @jar;
print $fetched->{flavour} eq 'child' && $jar[-1]{flavour} eq 'parent'
    ? "ok 4 - shared array releases objects only through canonical mutation\n"
    : "not ok 4 - shared array releases objects only through canonical mutation\n";
