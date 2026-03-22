// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/**
 * @title Interface do Contrato de Controlo de Acessos
 * @dev O enunciado exige que o ISTCoin chame um contrato externo para validar transferências.
 */
interface IAccessControl {
    function isAllowedToTransfer(address account) external view returns (bool);
}

/**
 * @title ISTCoin
 * @dev Implementação do token nativo ERC-20 para o projeto DepChain (Fase 2)
 */
contract ISTCoin is ERC20 {
    
    // Referência para o contrato de Controlo de Acessos
    IAccessControl public accessControlContract;

    /**
     * @dev Construtor do contrato.
     * @param _accessControlAddress O endereço do contrato de Controlo de Acessos já publicado na rede.
     */
    constructor(address _accessControlAddress) ERC20("IST Coin", "IST") {
        require(_accessControlAddress != address(0), "Endereco de Access Control invalido");
        accessControlContract = IAccessControl(_accessControlAddress);
        
        // Supply de 100 milhões com 2 casas decimais.
        // Cálculo: 100.000.000 * (10 ^ 2)
        _mint(msg.sender, 100000000 * 10**2);
    }

    /**
     * @dev Requisito do guião: O token deve ter 2 casas decimais (o default do Ethereum é 18).
     */
    function decimals() public view virtual override returns (uint8) {
        return 2;
    }

    /**
     * @dev Requisito do guião: Mitigação do ataque "Approval Frontrunning".
     * Exige que a allowance seja alterada para 0 antes de ser atualizada para outro valor.
     */
    function approve(address spender, uint256 amount) public virtual override returns (bool) {
        uint256 currentAllowance = allowance(_msgSender(), spender);
        
        // Bloqueia a alteração de um valor > 0 para outro valor > 0
        require(
            currentAllowance == 0 || amount == 0,
            "ISTCoin: Para evitar frontrunning, altere a permissao para 0 primeiro"
        );
        
        return super.approve(spender, amount);
    }

    /**
     * @dev Requisito do guião: Validar com o Access Control antes de um 'transfer'.
     */
    function transfer(address to, uint256 amount) public virtual override returns (bool) {
        // Verifica se quem está a invocar a transação tem permissão
        require(accessControlContract.isAllowedToTransfer(_msgSender()), "ISTCoin: Transferencia bloqueada pelo Access Control");
        
        return super.transfer(to, amount);
    }

    /**
     * @dev Requisito do guião: Validar com o Access Control antes de um 'transferFrom'.
     */
    function transferFrom(address from, address to, uint256 amount) public virtual override returns (bool) {
        // Verifica se quem está a invocar a transação (o spender) tem permissão
        require(accessControlContract.isAllowedToTransfer(_msgSender()), "ISTCoin: Transferencia bloqueada pelo Access Control");
        
        return super.transferFrom(from, to, amount);
    }
}