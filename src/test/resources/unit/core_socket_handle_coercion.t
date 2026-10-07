use strict;
use warnings;
use Test::More tests => 8;

{
    no strict 'refs';
    is &CORE::accept('foo', 'bar'), undef,
        'accept returns undef for a non-handle source';
}

my ($new_socket, $listen_socket);
eval { &CORE::accept($new_socket, $listen_socket) };
like $@, qr/^Can't use an undefined value as a symbol reference at /,
    'accept rejects an undefined source handle';
is ref $new_socket, 'GLOB',
    'accept vivifies its destination before rejecting the source handle';

{
    no strict 'refs';
    my ($new_socket, $listen_socket);
    local $SIG{__WARN__} = sub {};
    eval { &CORE::accept($new_socket, $listen_socket) };
    is $@, '', 'accept permits an undefined source without strict refs';
    is ref $new_socket, 'GLOB',
        'accept vivifies its destination without strict refs';
    is $listen_socket, undef,
        'accept leaves its undefined source unchanged without strict refs';
}

{
    no strict 'refs';
    is &CORE::bind('foo', 'bear'), undef,
        'bind returns undef for a non-handle literal';
}

{
    no strict 'refs';
    my $bind_socket;
    eval { &CORE::bind($bind_socket, 'bear') };
    like $@, qr/^Bad symbol for filehandle at /,
        'bind rejects an undefined lvalue as a filehandle';
}

done_testing;
