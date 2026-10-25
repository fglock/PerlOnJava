package GraphQL::MaybeTypeCheck;

use Attribute::Handlers;

sub import {
    my $caller = caller;
    no strict 'refs';
    push @{"${caller}::ISA"}, __PACKAGE__;
}

sub ReturnType : ATTR(CODE) { return }

1;
