package Encode;
use strict;
use warnings;
our $VERSION = '3.21';

require Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(decode encode encode_utf8 decode_utf8 find_encoding);
our @EXPORT_OK = qw(
    _utf8_off _utf8_on define_encoding from_to is_utf8
    perlio_ok resolve_alias
    encodings
    FB_DEFAULT FB_CROAK FB_QUIET FB_WARN FB_HTMLCREF FB_XMLCREF
    LEAVE_SRC
);

use XSLoader;
XSLoader::load('Encode', $VERSION);

{
    package Encode::utf8;
    our @ISA = qw(Encode::Encoding);
}

# Override find_encoding to add Encode::Alias support.
# The Java backend only recognises hardcoded charset names.  This wrapper
# consults Encode::Alias (loaded by modules like Encode::Locale) dynamically.
{
    no warnings 'redefine';
    my %_resolving;   # per-name recursion guard
    my %_encoding_cache;

    my $_cached_java_find_encoding = sub {
        my ($name) = @_;
        return undef unless defined $name;

        my $key = lc $name;
        return $_encoding_cache{$key} if exists $_encoding_cache{$key};

        my $enc = eval { _java_find_encoding($name) };
        if (defined $enc) {
            $_encoding_cache{$key} = $enc;
            eval {
                $_encoding_cache{lc $enc->name} ||= $enc;
                $_encoding_cache{lc $enc->mime_name} ||= $enc;
            };
        }
        return $enc;
    };

    *find_encoding = sub {
        my ($name, $skip_external) = @_;
        return undef unless defined $name;
        return $name
            if ref($name) && eval { $name->isa('Encode::Encoding') };

        # Guard against circular alias chains for the same name
        return undef if $_resolving{$name};
        local $_resolving{$name} = 1;

        # Consult Encode::Alias before the stable Java-encoding cache. Dynamic
        # aliases such as Encode::Locale's "locale" can be reinitialized at
        # runtime, so caching their resolved object here would make
        # Encode::Locale::reinit() ineffective.
        if (defined &Encode::Alias::find_alias) {
            my $resolved = eval { Encode::Alias::find_alias("Encode", $name) };
            if (defined $resolved) {
                return $resolved;
            }
        }

        my $key = lc $name;
        return $_encoding_cache{$key} if exists $_encoding_cache{$key};

        if ($key eq 'locale') {
            my $enc = $_cached_java_find_encoding->('UTF-8');
            $_encoding_cache{$key} = $enc if defined $enc;
            return $enc;
        }

        return $_cached_java_find_encoding->($name);
    };

}

sub resolve_alias {
    my ($name) = @_;
    my $enc = find_encoding($name);
    return unless defined $enc;
    return ref($enc) ? $enc->{Name} : $enc;
}

# Delegate to Encode::Alias for alias management.
# Modules like XML::SAX::PurePerl call Encode::define_alias() directly.
sub define_alias {
    require Encode::Alias;
    goto &Encode::Alias::define_alias;
}

1;
