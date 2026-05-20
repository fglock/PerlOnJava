use strict;
use warnings;
use Test::More tests => 8;
use Scalar::Util qw(refaddr weaken);

our %DEFERRED;
our %QUOTED;

sub defer_like {
    my ($maker) = @_;
    my $deferred;
    my $info = [ $maker ];

    $deferred = sub {
        $info if 0;
        return $maker->();
    };

    weaken($info->[1] = $deferred);
    weaken($DEFERRED{$deferred} = $info);
    return $deferred;
}

sub quote_like {
    my $quoted_info = { code => '{}' };
    my $deferred = defer_like(sub {
        $quoted_info if 0;
        return sub { 1 };
    });

    weaken($quoted_info->{deferred} = $deferred);
    weaken($QUOTED{$deferred} = $quoted_info);
    return $deferred;
}

sub quoted_like_from {
    my ($sub) = @_;
    my $quoted_info = $QUOTED{$sub || ''} or return undef;
    return [ $quoted_info->{code} ]
        if $quoted_info->{deferred} && $quoted_info->{deferred} eq $sub;
    return undef;
}

sub clone_like {
    my @quoted = map {
        defined $_ && $_->{deferred} ? ($_->{deferred} => $_) : ()
    } values %QUOTED;
    %QUOTED = @quoted;
    weaken($_) for values %QUOTED;
}

sub named_lvalue_defer_like {
    my ($name) = @_;
    my ($pkg, $subname) = $name =~ /^(.*)::([^:]+)$/;
    my $info = [ $name, sub { 1 } ];
    my $code = qq[
        package $pkg;
        sub $subname :lvalue {
            package main;
            \$info->[0];
            my \$x;
            \$x;
        }
        \\&$subname
    ];
    my $deferred = eval $code;
    die $@ if $@;
    weaken($DEFERRED{$deferred} = $info);
    return $deferred;
}

sub _getglob {
    no strict 'refs';
    \*{$_[0]};
}

sub no_subname_undefer_like {
    my ($deferred) = @_;
    my $info = $DEFERRED{$deferred} or return $deferred;
    my ($target, $maker, $options, $undeferred_ref, $deferred_sub) = @$info;

    return $$undeferred_ref if $$undeferred_ref;
    $$undeferred_ref = my $made = $maker->();

    if (defined($target) && ($deferred eq (*{_getglob($target)}{CODE} || ''))) {
        no warnings 'redefine';
        *{_getglob($target)} = $made;
    }
    my $undefer_info = [ $target, $maker, $options, $undeferred_ref ];
    $info->[5] = $DEFERRED{$made} = $undefer_info;
    weaken ${$undefer_info->[3]};

    return $made;
}

sub no_subname_defer_info_like {
    my ($deferred) = @_;
    my $info = $DEFERRED{$deferred || ''} or return undef;
    my ($target, $maker, $options, $undeferred_ref, $deferred_sub) = @$info;

    if (!(($deferred_sub && $deferred eq $deferred_sub)
            || ($$undeferred_ref && $deferred eq $$undeferred_ref))) {
        delete $DEFERRED{$deferred};
        return undef;
    }
    return [
        $target, $maker, $options,
        ($undeferred_ref && $$undeferred_ref ? $$undeferred_ref : ()),
    ];
}

sub no_subname_defer_like {
    my ($name, $maker) = @_;
    my ($pkg, $subname) = $name =~ /^(.*)::([^:]+)$/;
    my $deferred;
    my $undeferred;
    my $info = [ $name, $maker, undef, \$undeferred ];
    my $code = qq[
        package $pkg;
        sub $subname {
            package main;
            \$undeferred ||= no_subname_undefer_like(\$info->[4]);
            goto &\$undeferred;
        }
        \\&$subname
    ];
    $deferred = eval $code;
    die $@ if $@;
    weaken($info->[4] = $deferred);
    weaken($DEFERRED{$deferred} = $info);
    return $deferred;
}

{
    my $foo = quote_like();
    my $foo_string = "$foo";
    undef $foo;

    is quoted_like_from($foo_string), undef,
        'quoted metadata weak entry clears when deferred sub dies';

    clone_like();
    ok !exists $QUOTED{$foo_string},
        'CLONE drops expired quoted metadata';
}

{
    my $foo = quote_like();
    my $foo_string = "$foo";
    clone_like();
    undef $foo;

    is quoted_like_from($foo_string), undef,
        'CLONE does not strengthen deferred sub weak refs';
}

{
    my $foo = quote_like();
    my $foo_string = "$foo";
    my $quoted_info = $QUOTED{$foo_string};
    undef $foo;

    clone_like();
    ok !exists $QUOTED{$foo_string},
        'CLONE removes expired entry even when raw info hash is held';
}

{
    my $deferred = named_lvalue_defer_like('POJ_SubQuoteUnit::blorp');

    ok $DEFERRED{$deferred},
        'named lvalue deferred sub keeps weak metadata before first call';

    {
        no strict 'refs';
        delete $POJ_SubQuoteUnit::{blorp};
    }
}

{
    my $guff;
    my $deferred = no_subname_defer_like(
        'POJ_SubQuoteUnit::gwarf',
        sub { sub { $guff } },
    );
    my $undeferred = no_subname_undefer_like($deferred);
    my $undeferred_addr = refaddr($undeferred);
    my $undeferred_str = "$undeferred";

    {
        no strict 'refs';
        delete $POJ_SubQuoteUnit::{gwarf};
    }

    weaken($deferred);
    weaken($undeferred);

    is $undeferred, undef,
        'former stash-installed undeferred sub weak ref clears';

    is no_subname_defer_info_like($undeferred_str), undef,
        'former stash-installed undeferred metadata clears';

    isnt refaddr(no_subname_undefer_like($undeferred_str)), $undeferred_addr,
        'former stash-installed undeferred sub is not reused after expiry';
}
